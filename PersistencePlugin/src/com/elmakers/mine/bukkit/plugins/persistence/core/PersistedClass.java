package com.elmakers.mine.bukkit.plugins.persistence.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

import com.elmakers.mine.bukkit.plugins.persistence.Persistence;
import com.elmakers.mine.bukkit.plugins.persistence.PersistencePlugin;
import com.elmakers.mine.bukkit.plugins.persistence.annotation.Persist;
import com.elmakers.mine.bukkit.plugins.persistence.annotation.PersistClass;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataRow;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataTable;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataStore;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataType;

/**
 * Represents and manages a single persisted class.
 * 
 * This class binds to a Class, looking for @Persist and @PersistClass
 * tags.
 * 
 * It will keep track of the persisted fields of a class, and handle
 * creating and caching object instances of that class.
 * 
 * @author NathanWolf
 *
 */
public class PersistedClass
{
	public PersistedClass()
	{
		
	}
	
	public PersistedClass(PersistedClass copy, PersistedField container)
	{
		this.defaultStore = copy.defaultStore;
		this.container = container;
		bind(copy.persistClass);
	}
	
	public boolean bind(Class<? extends Object> persistClass)
	{
		this.persistClass = persistClass;
		
		/*
		 * Set up persisted class
		 */
		PersistClass classSettings = persistClass.getAnnotation(PersistClass.class);
		
		if (classSettings == null)
		{
			log.warning("Persistence: class " + persistClass.getName() + " does not have the @PersistClass annotation.");
			return false;
		}		

		cacheObjects = classSettings.cache();
		schema = classSettings.schema();
		name = classSettings.name();
		contained = classSettings.contained();
		
		name = name.replace(" ", "_");
		schema = schema.replace(" ", "_");
		
		defaultStore = Persistence.getInstance().getStore(schema);
		
		if (!cacheObjects)
		{
			log.warning("Persistence: class " + persistClass.getName() + ": non-cached objects no supported, yet.");
			return false;
		}
		
		/*
		 * Find fields, getters and setters
		 */
		
		idField = null;

		for (Field classField : persistClass.getDeclaredFields())
		{
			Persist persist = classField.getAnnotation(Persist.class);
			if (persist != null)
			{
				PersistedField field = PersistedField.tryCreate(classField, this);		
				if (field == null)
				{
					log.warning("Persistence: Field " + persistClass.getName() + "." + classField.getName() + " is not persistable, type=" + classField.getType().getName());
				}
				else
				{
					addField(field, persist);
				}
			}
		}
	
		for (Method method : persistClass.getDeclaredMethods())
		{
			Persist persist = method.getAnnotation(Persist.class);
			if (persist != null)
			{
				PersistedField field = PersistedField.tryCreate(method, this);
				if (field == null)
				{
					log.warning("Persistence: Field " + persistClass.getName() + "." + method.getName() + " is not persistable, type=" + method.getReturnType().getName());
				}
				else
				{
					addField(field, persist);
				}
			}
		}
		
		if (idField == null && !contained)
		{
			log.warning("Persistence: class " + persistClass.getName() + ": must specify one id field. Use an auto int if you need.");
			return false;
		}
		
		return (fields.size() > 0);
	}
	
	public boolean addField(PersistedField field, Persist persist)
	{
		if (persist.id())
		{
			if (idField != null)
			{
				log.warning("Persistence: class " + persistClass.getName() + ": can't have more than one id field");
				return false;
			}
			if (persist.contained())
			{
				log.warning("Persistence: class " + persistClass.getName() + ": an id field can't be a contained entity");
				return false;
			}
			idField = field;
			field.setIsIdField(true);
		}
		
		if (field instanceof PersistedList)
		{
			PersistedList list = (PersistedList)field;
			if (list.getListDataType() == DataType.LIST)
			{
				log.warning("Persistence: class " + persistClass.getName() + ": lists of lists not supported");
				return false;
			}
			externalFields.add(list);
			list.setContained(persist.contained());
		}
		else if (field instanceof PersistedReference)
		{
			PersistedReference reference = (PersistedReference)field;
			internalFields.add(reference);
			reference.setContained(persist.contained());
		}
		else
		{
			if (persist.contained())
			{
				log.warning("Persistence: class " + persistClass.getName() + ": only List and Object fields may be contained");
				return false;
			}
			if (persist.auto())
			{
				if (!persist.id())
				{
					log.warning("Persistence: class " + persistClass.getName() + ": only id fields may be autogenerated");
				}
				else if (!field.getType().isAssignableFrom(Integer.class) && !field.getType().isAssignableFrom(int.class))
				{
					log.warning("Persistence: class " + persistClass.getName() + ": only integer fields may be autogenerated");
				}
				else
				{
					field.setAutogenerate(persist.auto());
				}
			}
			internalFields.add(field);
		}
		
		field.setContainer(container);

		fields.add(field);
		
		return true;
	}
	
	public void bindReferences()
	{
		for (PersistedField field : fields)
		{
			field.bind();
		}
	}
	
	public void put(Object o)
	{
		checkLoadCache();
				
		Object id = getId(o);
		CachedObject co = cacheMap.get(id);
		if (co == null)
		{
			co = addToCache(o);
		}
		
		// TODO: merge
		co.setCached(cacheObjects);
		co.setObject(o);
		dirty = true;
	}
	
	public void remove(Object o)
	{
		Object id = getId(o);
		removeFromCache(id);
		dirty = true;
	}

	public Object get(Object id)
	{
		checkLoadCache();
		CachedObject cached = cacheMap.get(id);
		if (cached == null) return null;
		return cached.getObject();
	}
	
	@SuppressWarnings("unchecked")
	public <T> void getAll(List<T> objects)
	{
		checkLoadCache();
		for (CachedObject cachedObject : cache)
		{
			Object object = cachedObject.getObject();
			if (persistClass.isAssignableFrom(object.getClass()))
			{
				objects.add((T)object);
			}
		}
	}
	
	public void putAll(List<? extends Object> objects)
	{
		checkLoadCache();
		// TODO: merge...
	}

	public Object get(Object id, Object defaultValue)
	{
		checkLoadCache();
		CachedObject cached = cacheMap.get(id);
		if (cached == null)
		{
			cached = addToCache(defaultValue);
			
		}
		return cached.getObject();
	}
	
	public void clear()
	{
		cacheMap.clear();
		cache.clear();
		loadState = LoadState.UNLOADED;
	}
	
	public void reset()
	{
		reset(defaultStore);
	}
	
	public void reset(DataStore store)
	{
		if (!store.connect()) return;
		
		DataTable resetTable = getClassTable(); 
		store.reset(resetTable);
		
		// Reset any list sub-tables
		for (PersistedList list : externalFields)
		{
			DataTable listTable = getListTable(list);
			store.reset(listTable);
		}
		
		maxId = 1;
	}
	
	public boolean isDirty()
	{
		return dirty;
	}
	
	public int getFieldCount()
	{
		return fields.size();
	}
	
	public String getTableName()
	{
		return name;
	}
	
	public String getSchema()
	{
		return schema;
	}
	
	public Class<? extends Object> getType()
	{
		return persistClass;
	}
	
	public PersistedField getIdField()
	{
		return idField;
	}
	
	public List<PersistedField> getInternalFields()
	{
		return internalFields;
	}
	
	public List<PersistedList> getExternalFields()
	{
		return externalFields;
	}
	
	public List<PersistedField> getPersistedFields()
	{
		return fields;
	}
	
	public void save()
	{
		save(defaultStore);
	}
	
	public void save(DataStore store)
	{
		if (loadState != LoadState.LOADED) return;
		if (!dirty) return;
		
		// Drop removed objects
		if (removedFromCache.size() > 0)
		{
			DataTable clearTable = getClassTable();
			populate(clearTable, removedFromCache);			
			store.clear(clearTable);
			
			removedFromCache.clear();
			removedMap.clear();
		}
		
		// Save dirty objects
		List<CachedObject> dirtyObjects = new ArrayList<CachedObject>();
		for (CachedObject cached : cache)
		{
			if (cached.isDirty())
			{
				dirtyObjects.add(cached);
			}
		}
		
		save(dirtyObjects, store);
		dirty = false;
	}
	
	protected void populate(DataTable dataTable, List<CachedObject> instances)
	{
		for (CachedObject instance : instances)
		{
			DataRow instanceRow = new DataRow(dataTable);
			populate(instanceRow, instance.getObject());
			dataTable.addRow(instanceRow);	
		}
	}
	
	public void populate(DataRow row, Object instance)
	{
		for (PersistedField field : internalFields)
		{
			field.save(row, instance);
		}	
	}
	
	public void save(List<CachedObject> instances)
	{
		save(instances, defaultStore);
	}
	
	public void save(List<CachedObject> instances, DataStore store)
	{
		if (!store.connect()) return;
		
		// Save main class data
		DataTable classTable = getClassTable();
		populate(classTable, instances);
		store.save(classTable);
		
		// Save list data
		for (PersistedList list : externalFields)
		{
			DataTable listTable = getListTable(list);
			List<Object> instanceIds = new ArrayList<Object>();
			
			for (CachedObject instance : instances)
			{
				Object id = getId(instance.getObject());
				instanceIds.add(id);			
				list.save(listTable, instance.getObject());
			}
			
			// First, delete removed items
			store.clearIds(listTable, instanceIds);
			
			// Save new list data
			store.save(listTable);
		}	
		
		for (CachedObject cached : instances)
		{
			cached.setSaved();
		}
	}
	
	/*
	 * Protected members
	 */
	
	protected DataTable getClassTable()
	{
		DataTable classTable = new DataTable(getTableName());
		return classTable;
	}
	
	protected DataTable getListTable(PersistedList list)
	{
		DataTable listTable = new DataTable(list.getTableName());
		return listTable;
	}
	
	protected void checkLoadCache()
	{
		checkLoadCache(defaultStore);
	}
	
	protected void checkLoadCache(DataStore store)
	{
		if (loadState == LoadState.UNLOADED && cacheObjects)
		{
			loadState = LoadState.LOADING;
			try
			{
				if (store.connect())
				{
					validateTables(store);
					loadCache();
					loadState = LoadState.LOADED;
				}
			}
			catch(Exception e)
			{
				log.severe("Persistence: Exception loading cache for " + name);
				clear();
				e.printStackTrace();
				return;
			}
		}
	}
	
	protected void validateTables(DataStore store)
	{
		if (!store.connect())
		{
			return;
		}
		DataTable classTable = getClassTable();
		
		classTable.createHeader();
		populateHeader(classTable);
		
		store.validateTable(classTable);
		
		// Validate any list sub-tables
		for (PersistedList list : externalFields)
		{
			DataTable listTable = getListTable(list);
			listTable.createHeader();
			list.populateHeader(listTable);
			store.validateTable(listTable);
		}		
	}
	
	public String getContainedIdName()
	{
		String idName = getTableName();
		if (idField != null)
		{
			idName = PersistedField.getContainedName(idName, idField.getDataName());
		}
		return idName;
	}
	
	public String getContainedIdName(PersistedField container)
	{
		String idName = container.getDataName();
		if (idField != null)
		{
			idName = PersistedField.getContainedName(idName, idField.getDataName());
		}
		return idName;
	}
	
	public void populateHeader(DataTable table)
	{
		for (PersistedField field : internalFields)
		{
			field.populateHeader(table);
		}
	}
	
	protected void loadCache()
	{
		loadCache(defaultStore);
	}
	
	protected void loadCache(DataStore store)
	{
		if (!store.connect()) return;
		
		DataTable classTable = getClassTable();
		store.load(classTable);
		
		// Begin deferred referencing, to prevent the problem of DAO's referencing unloaded DAOs.
		// DAOs will be loaded recursively as needed,
		// and then all deferred references will be resolved afterward.
		PersistedReference.beginDefer();
		
		for (DataRow row : classTable.getRows())
		{
			Object newInstance = createInstance(row);
			if (newInstance != null)
			{
				if (idField.isAutogenerated())
				{
					int id = (Integer)idField.get(newInstance);
					if (id > maxId) maxId = id;
				}
				addToCache(newInstance);
			}
		}
		
		// Bind deferred references, to handle DAOs referencing other DAOs, even of the
		// Same type. 
		// DAOs will be loaded recursively as needed, and then references bound when everything has been
		// resolved.
		PersistedReference.endDefer();

		// Defer load lists of entities
		PersistedList.beginDefer();
		
		// Load list data
		if (externalFields.size() > 0)
		{
			List<Object> instances = new ArrayList<Object>();
			for (CachedObject cached : cache)
			{
				instances.add(cached.getObject());
			}
			for (PersistedList list : externalFields)
			{
				DataTable listTable = getListTable(list);
				store.load(listTable);
				list.load(listTable, instances);
			}
		}
		
		// Load any reference lists
		PersistedList.endDefer();
	}
	
	public void load(DataRow row, Object o)
	{
        for (PersistedField field : internalFields)
        {
    		try
    		{
    			field.load(row, o);
    		}
			catch (Exception ex)
			{
				log.warning("Persistence error getting field " + field.getName() + " for " + getTableName() + ": " + ex.getMessage());	
	        }
        }
	}
	
	protected Object createInstance(DataRow row)
	{
		Object newObject = null;
		
		try
		{
			newObject = persistClass.newInstance();
			load(row, newObject);
		}
		catch (IllegalAccessException ex)
		{
			newObject = null;
			log.warning("Persistence error creating instance of " + getTableName() + ": " + ex.getMessage());		
		}
		catch (InstantiationException ex)
		{
			newObject = null;
			log.warning("Persistence error creating instance of " + getTableName() + ": " + ex.getMessage());		
		}

		return newObject;
	}
	
	public Object getId(Object o)
	{
		Object value = null;
		if (idField != null)
		{
			value = idField.get(o);
		}
		return value;
	}
	
	protected CachedObject addToCache(Object o)
	{
		// Check to see if this object has already been removed
		// If the ids are autogenerated, just assume it's a new object.
		boolean autogenerate = idField.isAutogenerated();
		Object id = null;
		if (!autogenerate)
		{
			CachedObject removedObject = removedMap.get(id);
			if (removedObject != null)
			{
				removedMap.remove(id);
				removedFromCache.remove(removedObject);
			}
			id = getId(o);
		}
		else
		{
			id = maxId++;
			idField.set(o, id);
		}

		CachedObject cached = new CachedObject(o);
		cache.add(cached);
		cacheMap.put(id, cached);
		
		return cached;
	}
	
	protected void removeFromCache(Object id)
	{
		CachedObject co = cacheMap.get(id);
		if (co == null)
		{
			return;
		}
		
		cache.remove(co);
		cacheMap.remove(id);
		removedFromCache.add(co);
		removedMap.put(id, co);
	}
	
	/*
	 * Private data
	 */
	
	enum LoadState
	{
		UNLOADED,
		LOADING,
		LOADED,
	}
	
	protected boolean						dirty				= false;
	protected LoadState						loadState			= LoadState.UNLOADED;

	protected boolean						contained			= false;
	protected boolean						cacheObjects		= false;
	protected DataStore						defaultStore		= null;
	protected int							maxId				= 1;

	protected HashMap<Object, CachedObject>	cacheMap			= new HashMap<Object, CachedObject>();
	protected List<CachedObject>			cache				= new ArrayList<CachedObject>();
	protected HashMap<Object, CachedObject>	removedMap			= new HashMap<Object, CachedObject>();
	protected List<CachedObject>			removedFromCache	= new ArrayList<CachedObject>();

	protected Class<? extends Object>		persistClass;

	protected List<PersistedField>			fields				= new ArrayList<PersistedField>();
	protected List<PersistedField>			internalFields		= new ArrayList<PersistedField>();
	protected List<PersistedList>			externalFields		= new ArrayList<PersistedList>();

	protected PersistedField 				idField 			= null;
	protected PersistedField				container 			= null;

	protected String						schema 				= null;
	protected String						name 				= null;

	protected static Logger					log					= PersistencePlugin.getLogger();
}

package com.elmakers.mine.bukkit.plugins.persistence.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import com.elmakers.mine.bukkit.plugins.persistence.PersistencePlugin;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataField;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataRow;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataTable;
import com.elmakers.mine.bukkit.plugins.persistence.data.DataType;

public class PersistedField
{
	public PersistedField(Method getter, Method setter)
	{
		this.name = getNameFromMethod(setter);
		this.getter = getter;
		this.setter = setter;
		this.field = null;
	}
	
	public PersistedField(Field field)
	{
		this.name = field.getName();
		this.field = field;
		this.getter = null;
		this.setter = null;
	}
	
	public Class<?> getType()
	{
		if (getter != null)
		{
			return getter.getReturnType();
		}
		if (field != null)
		{
			return field.getType();
		}
		return null;
	}
	
	public String getName()
	{
		return name;
	}

	public String getDataName()
	{
		if (container != null)
		{
			return getContainedName(container.getDataName(), name);
		}
		return name;
	}
	
	public boolean set(Object o, Object value)
	{
		if (setter != null)
		{
			try
			{
				setter.invoke(o, value);
			}
			catch(InvocationTargetException e)
			{
				return false;
			}
			catch(IllegalAccessException e)
			{
				return false;
			}
		}
		
		if (field != null)
		{
			try
			{
				field.set(o, value);
			}
			catch(IllegalAccessException e)
			{
				return false;
			}
		}
		return true;
	}
	
	public Object get(Object o)
	{
		Object result = null;
		if (getter != null)
		{
			try
			{
				result = getter.invoke(o);
			}
			catch(InvocationTargetException e)
			{
				result = null;
			}
			catch(IllegalAccessException e)
			{
				result = null;
			}
		}
		
		if (result == null && field != null)
		{
			try
			{
				result = field.get(o);
			}
			catch(IllegalAccessException e)
			{
				result = null;
			}
		}
		
		return result;
	}
	
	public void populateHeader(DataTable dataTable, PersistedField container)
	{
		DataRow headerRow = dataTable.getHeader();
		DataField field = new DataField(getDataName(), getDataType());
		field.setIdField(isIdField());
		field.setAutogenerated(isAutogenerated());
		headerRow.add(field);
		if (isIdField())
		{
			dataTable.addIdFieldName(getName());
		}
	}
	
	public void populateHeader(DataTable dataTable)
	{
		populateHeader(dataTable, null);
	}
		
	public static String getContainedName(String container, String contained)
	{
		String remainingContained = "";
		if (contained.length() > 1)
		{
			remainingContained = contained.substring(1);
		}
		
		// De plural-ize
		container = dePluralize(container);
		contained = container + contained.substring(0, 1).toUpperCase() + remainingContained;
		
		// Also de-pluralize results
		if (contained.length() > 1 && contained.charAt(contained.length() - 1) == 's')
		{
			contained = contained.substring(0, contained.length() - 1);
		}
		return dePluralize(contained);
	}
	
	public static String dePluralize(String plural)
	{
		// Special cases- kinda hacky, but makes for clean schemas.
		if (plural.equals("children")) return "child";
		if (plural.equals("Children")) return "Child";
				
		if (plural.length() > 1 && plural.charAt(plural.length() - 1) == 's')
		{
			plural = plural.substring(0, plural.length() - 1);
		}
		
		return plural;
	}
	
	public void save(DataRow row, Object o)
	{
		Object data = null;
		if (o != null)
		{
			data = get(o);
		}
		DataField field = new DataField(getDataName(), getDataType(), data);
		row.add(field);
	}
	
	public void load(DataRow row, Object o)
	{
		DataField dataField = row.get(getDataName());
		Object value = dataField.getValue();
		DataType dataType = getDataType();
		DataType valueType = dataField.getType();
		if (dataType != valueType)
		{
			value = DataType.convertTo(value, dataType);
		}
		set(o, value);
	}
	
	public DataType getDataType()
	{
		Class<?> fieldType = getType();
		return DataType.getTypeFromClass(fieldType);
	}
	
	public boolean isIdField()
	{
		return idField;
	}
	
	public void setIsIdField(boolean isId)
	{
		idField = isId;
	}
	
	public static PersistedField tryCreate(Field field, PersistedClass owningClass)
	{
		DataType dataType = DataType.getTypeFromClass(field.getType());
		PersistedField pField = null;

		if (dataType == DataType.OBJECT)
		{
			pField = new PersistedReference(field);
		}
		else if (dataType != DataType.NULL)
		{
			pField = new PersistedField(field);
		}
		else if (dataType == DataType.LIST)
		{
			pField = new PersistedList(field, owningClass);
		}
		
		return pField;
	}
	
	public static PersistedField tryCreate(Method getterOrSetter, PersistedClass owningClass)
	{
		Method setter = null;
		Method getter = null;
		String fieldName = getNameFromMethod(getterOrSetter);
		Class<? extends Object> persistClass = owningClass.getType();
		
		if (fieldName.length() == 0)
		{
			return null;
		}
		
		if (isSetter(getterOrSetter))
		{
			setter = getterOrSetter;
			getter = findGetter(setter, persistClass);
		}
		else if (isGetter(getterOrSetter))
		{
			getter = getterOrSetter;
			setter = findSetter(getter, persistClass);
		}
		if (setter == null || getter == null)
		{
			return null;
		}
		
		PersistedField pField = null;
		DataType dataType = DataType.getTypeFromClass(getter.getReturnType());
		
		if (dataType == DataType.OBJECT)
		{
			pField = new PersistedReference(getter, setter);
		}
		else if (dataType == DataType.LIST)
		{
			pField = new PersistedList(getter, setter, owningClass);
		}
		else if (dataType != DataType.NULL)
		{
			pField = new PersistedField(getter, setter);
		}

		return pField;
	}
	
	public static boolean isGetter(Method method)
	{
		String methodName = method.getName();
		boolean hasGet = methodName.substring(0, 3).equals("get");
		boolean hasIs = methodName.substring(0, 2).equals("is");
		
		return hasIs || hasGet;
	}
	
	public static boolean isSetter(Method method)
	{
		String methodName = method.getName();
		return methodName.substring(0, 3).equals("set");
	}
	
	public static String getNameFromMethod(Method method)
	{
		String methodName = method.getName();
		return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
	}
	
	public static Method findSetter(Method getter, Class<? extends Object> c)
	{
		Method setter = null;
		String methodName = getter.getName();
		String name = "s" + methodName.substring(1);
		if (methodName.substring(0, 2).equals("is"))
		{
			name = "set" + methodName.substring(2);
		}
		try
		{
			setter = c.getMethod(name, getter.getReturnType());
		}
		catch (NoSuchMethodException e)
		{
			setter = null;
		}
		return setter;
	}
	
	public static Method findGetter(Method setter, Class<?> c)
	{
		Method getter = null;
		String name = "g" + setter.getName().substring(1);
		try
		{
			getter = c.getMethod(name);
		}
		catch (NoSuchMethodException e)
		{
			getter = null;
		}
		if (getter == null)
		{
			name = "is" + setter.getName().substring(3);
			try
			{
				getter = c.getMethod(name);
			}
			catch (NoSuchMethodException e)
			{
				getter = null;
			}
		}
		return getter;
	}
	
	public void bind()
	{
		
	}
	
	public void setContained(boolean contained)
	{
		this.contained = contained;
	}
	
	public void setContainer(PersistedField container)
	{
		this.container = container;
	}
	
	public void setAutogenerate(boolean autogen)
	{
		this.autogenerate = autogen;
	}
	
	public boolean isContained()
	{
		return contained;
	}
	
	public boolean isAutogenerated()
	{
		return autogenerate;
	}
	
	protected PersistedField	container		= null;
	protected Method			getter			= null;
	protected Method			setter			= null;
	protected Field				field			= null;
	protected String			name			= null;
	protected boolean			idField			= false;
	protected boolean			contained		= false;
	protected boolean			autogenerate	= false;

	protected static Logger	log				= PersistencePlugin.getLogger();
}

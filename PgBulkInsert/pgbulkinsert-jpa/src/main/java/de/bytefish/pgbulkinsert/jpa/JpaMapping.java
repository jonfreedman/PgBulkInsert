// Copyright (c) Philipp Wagner. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package de.bytefish.pgbulkinsert.jpa;

import de.bytefish.pgbulkinsert.jpa.annotations.PostgresDataType;
import de.bytefish.pgbulkinsert.jpa.mappings.IPostgresTypeMapping;
import de.bytefish.pgbulkinsert.jpa.mappings.PostgresTypeMapping;
import de.bytefish.pgbulkinsert.mapping.AbstractMapping;
import de.bytefish.pgbulkinsert.pgsql.constants.DataType;
import org.reflections.ReflectionUtils;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.withAnnotation;
import static org.reflections.ReflectionUtils.withModifier;
import static org.reflections.ReflectionUtils.withParametersCount;
import static org.reflections.ReflectionUtils.withPrefix;

public class JpaMapping<TEntity> extends AbstractMapping<TEntity> {

    private final Class<TEntity> entityClass;

    private final IPostgresTypeMapping typeMapping;
    private final Map<String, DataType> columnMapping;

    public JpaMapping(Class<TEntity> entityClass) {
        this(entityClass, new PostgresTypeMapping());
    }

    public JpaMapping(Class<TEntity> entityClass, Map<String, DataType> columnMapping) {
        this(entityClass, new PostgresTypeMapping(), columnMapping);
    }

    public JpaMapping(Class<TEntity> entityClass, IPostgresTypeMapping typeMapping) {
        this(entityClass, typeMapping, new HashMap<>());
    }

    public JpaMapping(Class<TEntity> entityClass, IPostgresTypeMapping typeMapping, Map<String, DataType> columnMapping) {
        this(entityClass, typeMapping, columnMapping, true);
    }

    public JpaMapping(Class<TEntity> entityClass, IPostgresTypeMapping typeMapping, Map<String, DataType> columnMapping, boolean usePostgresQuoting) {

        super(getSchemaName(entityClass), getTableName(entityClass), usePostgresQuoting);

        if(entityClass == null) {
            throw new IllegalArgumentException("entityClass");
        }

        this.entityClass = entityClass;
        this.typeMapping = typeMapping;
        this.columnMapping = columnMapping;

        processDataTypeAnnotations(columnMapping);
        mapFields(entityClass, typeMapping, columnMapping);
    }

    public Class<TEntity> getEntityClass() {
        return entityClass;
    }

    public IPostgresTypeMapping getTypeMapping() {
        return typeMapping;
    }

    private void processDataTypeAnnotations(Map<String, DataType> columnMapping) {
        Set<Field> fields = getAllFields(entityClass);

        for(Field field : fields) {

            Set<Annotation> annotations = ReflectionUtils.getAnnotations(field);

            if (annotations == null) {
                return;
            }

            for (Annotation annotation : annotations) {

                if (annotation instanceof PostgresDataType) {
                    PostgresDataType postgresDataType = (PostgresDataType) annotation;

                    columnMapping.put(postgresDataType.columnName(), postgresDataType.dataType());
                }

            }
        }
    }

    private void mapFields(Class<TEntity> entityClass, IPostgresTypeMapping typeMapping, Map<String, DataType> columnMapping) {
        try {
            internalMapFields(entityClass, typeMapping, columnMapping);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void internalMapFields(Class<TEntity> entityClass, IPostgresTypeMapping typeMapping, Map<String, DataType> columnMapping) throws Exception {

        Set<Method> getters = getAllMethods(entityClass, withModifier(Modifier.PUBLIC), withPrefix("get"), withParametersCount(0));

        getters.addAll(getAllMethods(entityClass, withModifier(Modifier.PUBLIC), withPrefix("is"), withParametersCount(0)));

        for (Field f : getAllFields(entityClass, withAnnotation(Column.class))) {

            // Get the Column to match in Postgres:
            Column column = f.getAnnotation(Column.class);

            String columnName = column.name();
            Type fieldType = f.getType();
            Method fieldGetter = findGetter(getters, f.getName());

            // TODO What should we do, if the Getter is null? Let it crash or just go to the next one?
            if(fieldGetter == null) {
                continue;
            }

            // Is this Field an Enum?
            Enumerated enumerated = f.getAnnotation(Enumerated.class);
            if(enumerated != null) {
                mapEnum(columnName, typeMapping, columnMapping, enumerated, fieldGetter);
            } else {
                mapField(columnName, typeMapping, columnMapping, fieldType, fieldGetter);
            }
        }
    }

    private Method findGetter(Set<Method> getters, String name) {
        for (Method fieldGetter : getters) {
            if (fieldGetter.getName().toUpperCase().endsWith(name.toUpperCase())) {
                return fieldGetter;
            }
        }
        return null;
    }

    private void mapEnum(String columnName, IPostgresTypeMapping typeMapping, Map<String, DataType> columnMapping, Enumerated enumerated, Method fieldGetter) {
        if(enumerated.value() == EnumType.ORDINAL) {
            // If we know which type to map this ordinal to, let's use it:
            if(columnMapping.containsKey(columnName)) {
                final DataType dataType = columnMapping.get(columnName);

                map(columnName, dataType,  tEntity -> {
                    Enum<?> enumeration = (Enum<?>) internalInvoke(fieldGetter, tEntity);

                    if(enumeration == null) {
                        return null;
                    }

                    return enumeration.ordinal();
                });
            }
            // ... or we make a best guess and use a short:
            else {
                // Use the Default Short:
                mapShort(columnName, new Function<TEntity, Number>() {
                    @Override
                    public Short apply(TEntity tEntity) {
                        Enum<?> enumeration = (Enum<?>) internalInvoke(fieldGetter, tEntity);

                        // Do we need to use a Default-value, if null?
                        if(enumeration == null) {
                            return null;
                        }

                        return (short) enumeration.ordinal();
                    }
                });
            }
        }
        // The Enumerated defined to store the Enum as a String:
        else if(enumerated.value() == EnumType.STRING) {
            mapText(columnName, new Function<TEntity, String>() {
                @Override
                public String apply(TEntity tEntity) {

                    // Do we need to use a Default-value, if null?
                    Enum<?> enumeration =  (Enum<?>) internalInvoke(fieldGetter, tEntity);

                    if(enumeration == null) {
                        return null;
                    }

                    return enumeration.name();
                }
            });
        }
    }

    private void mapField(String columnName, IPostgresTypeMapping typeMapping, Map<String, DataType> postgresColumnMapping, Type fieldType, Method fieldGetter) {

        // If we know which Postgres DataType to map to, let's use it:
        if(postgresColumnMapping.containsKey(columnName)) {
            final DataType dataType = postgresColumnMapping.get(columnName);

            map(columnName, dataType,  tEntity -> internalInvoke(fieldGetter, tEntity));
        // Or we fall back to the default Mappings:
        } else {
            DataType dataType = typeMapping.getDataType(fieldType);

            map(columnName, dataType,  tEntity -> internalInvoke(fieldGetter, tEntity));
        }
    }

    private Object internalInvoke(Method method, TEntity obj) {
        try {
           return method.invoke(obj);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> String getTableName(Class<T> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);

        return (table == null) ? "" : table.name();
    }

    public static <T> String getSchemaName(Class<T> entityClass) {

        Table table = entityClass.getAnnotation(Table.class);

        return (table == null) ? "" : table.schema();
    }
}

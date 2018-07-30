/*
 *  Copyright 2018 original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.cloud.gcp.data.spanner.core.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;

import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Type;

import org.springframework.cloud.gcp.data.spanner.core.convert.ConversionUtils;
import org.springframework.cloud.gcp.data.spanner.core.convert.SpannerEntityProcessor;
import org.springframework.cloud.gcp.data.spanner.core.convert.SpannerTypeMapper;
import org.springframework.cloud.gcp.data.spanner.core.mapping.SpannerDataException;
import org.springframework.cloud.gcp.data.spanner.core.mapping.SpannerMappingContext;
import org.springframework.cloud.gcp.data.spanner.core.mapping.SpannerPersistentEntity;
import org.springframework.cloud.gcp.data.spanner.core.mapping.SpannerPersistentProperty;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.util.Assert;

/**
 * Contains functions related to the table schema of entities.
 *
 * @author Chengyuan Zhao
 *
 * @since 1.1
 */
public class SpannerSchemaUtils {

	private final SpannerMappingContext mappingContext;

	private final SpannerEntityProcessor spannerEntityProcessor;

	private final SpannerTypeMapper spannerTypeMapper;

	public SpannerSchemaUtils(SpannerMappingContext mappingContext,
			SpannerEntityProcessor spannerEntityProcessor) {
		Assert.notNull(mappingContext,
				"A valid mapping context for Cloud Spanner is required.");
		Assert.notNull(spannerEntityProcessor,
				"A valid results mapper for Cloud Spanner is required.");
		this.mappingContext = mappingContext;
		this.spannerEntityProcessor = spannerEntityProcessor;
		this.spannerTypeMapper = new SpannerTypeMapper();
	}

	/**
	 * Gets the DDL string to drop the table for the given entity in Cloud Spanner.
	 * @param entityClass the entity type.
	 * @return the DDL string.
	 */
	public String getDropTableDdlString(Class entityClass) {
		return "DROP TABLE "
				+ this.mappingContext.getPersistentEntity(entityClass).tableName();
	}

	/**
	 * Gets the key for the given object.
	 * @param object the object to get the key for
	 * @return the Spanner Key for the given object.
	 */
	public Key getKey(Object object) {
		SpannerPersistentEntity persistentEntity = this.mappingContext
				.getPersistentEntity(object.getClass());
		return (Key) persistentEntity.getPropertyAccessor(object)
				.getProperty(persistentEntity.getIdProperty());
	}

	/**
	 * Gets the DDL string to create the table for the given entity in Cloud Spanner. This
	 * is just one of the possible schemas that can support the given entity type. The
	 * specific schema
	 * is determined by the configured property type converters used by the read and write
	 * methods in this SpannerOperations and will be compatible with those methods.
	 * @param entityClass the entity type.
	 * @return the DDL string.
	 */
	@SuppressWarnings("unchecked")
	public <T> String getCreateTableDdlString(Class<T> entityClass) {
		SpannerPersistentEntity<T> spannerPersistentEntity = (SpannerPersistentEntity<T>) this.mappingContext
				.getPersistentEntity(entityClass);

		StringBuilder stringBuilder = new StringBuilder(
				"CREATE TABLE " + spannerPersistentEntity.tableName() + " ( ");

		StringJoiner columnStrings = new StringJoiner(" , ");

		addColumnDdlStrings(spannerPersistentEntity, columnStrings);

		stringBuilder.append(columnStrings.toString()).append(" ) PRIMARY KEY ( ");

		StringJoiner keyStrings = new StringJoiner(" , ");

		addPrimaryKeyColumnNames(spannerPersistentEntity, keyStrings);

		stringBuilder.append(keyStrings.toString()).append(" )");
		return stringBuilder.toString();
	}

	/**
	 * Gets a list of DDL strings to create the tables rooted at the given entity class.
	 * The DDL-create strings are ordered in the list starting with the given root class
	 * and are topologically sorted.
	 * @param entityClass the root class for which to get create strings.
	 * @return a list of create strings that are toplogically sorted from parents to
	 * children.
	 */
	public List<String> getCreateTableDdlStringsForHierarchy(Class entityClass) {
		List<String> ddlStrings = new ArrayList<>();
		getCreateTableDdlStringsForHierarchy(null, entityClass, ddlStrings,
				new HashSet<>());
		return ddlStrings;
	}

	/**
	 * Gets the DDL strings to drop the tables of this entity and all of its sub-entities.
	 * The list is given in reverse topological sort, since parent tables cannot be
	 * dropped before their children tables.
	 * @param entityClass the root entity whose table to drop
	 * @return the list of drop DDL strings
	 */
	public List<String> getDropTableDdlStringsForHierarchy(Class entityClass) {
		List<String> ddlStrings = new ArrayList<>();
		getDropTableDdlStringsForHierarchy(entityClass, ddlStrings, new HashSet<>());
		return ddlStrings;
	}

	String getColumnDdlString(SpannerPersistentProperty spannerPersistentProperty,
			SpannerEntityProcessor spannerEntityProcessor) {
		Class columnType = spannerPersistentProperty.getType();
		String columnName = spannerPersistentProperty.getColumnName() + " ";
		Class spannerJavaType;
		Type.Code spannerColumnType;
		if (ConversionUtils.isIterableNonByteArrayType(columnType)) {
			Class innerType = spannerPersistentProperty.getColumnInnerType();
			spannerJavaType = spannerEntityProcessor
					.getCorrespondingSpannerJavaType(innerType, true);
			spannerColumnType = this.spannerTypeMapper
					.getSimpleTypeCodeForJavaType(spannerJavaType);

			if (spannerColumnType == null) {
				throw new SpannerDataException(
						"Could not find suitable Cloud Spanner column inner type for "
								+ "property type: " + innerType);
			}
			return columnName + getTypeDdlString(spannerColumnType, true,
					spannerPersistentProperty.getMaxColumnLength());
		}
		spannerJavaType = spannerEntityProcessor
					.getCorrespondingSpannerJavaType(columnType, false);

		if (spannerJavaType == null) {
			throw new SpannerDataException(
					"The currently configured custom type converters cannot "
							+ "convert the given type to a Cloud Spanner-compatible column type: "
							+ columnType);
		}

		spannerColumnType = spannerJavaType.isArray()
					? this.spannerTypeMapper.getArrayTypeCodeForJavaType(spannerJavaType)
					: this.spannerTypeMapper
							.getSimpleTypeCodeForJavaType(spannerJavaType);

		if (spannerColumnType == null) {
			throw new SpannerDataException(
					"Could not find suitable Cloud Spanner column type for property "
							+ "type :" + columnType);
		}

		return columnName + getTypeDdlString(spannerColumnType, spannerJavaType.isArray(),
					spannerPersistentProperty.getMaxColumnLength());
	}

	private void getCreateTableDdlStringsForHierarchy(String parentTable,
			Class entityClass, List<String> ddlStrings, Set<Class> seenClasses) {
		if (seenClasses.contains(entityClass)) {
			return;
		}
		ddlStrings.add(getCreateTableDdlString(entityClass) + (parentTable == null ? ""
				: ", INTERLEAVE IN PARENT " + parentTable + " ON DELETE CASCADE"));
		SpannerPersistentEntity spannerPersistentEntity = this.mappingContext
				.getPersistentEntity(entityClass);
		spannerPersistentEntity.doWithChildCollectionProperties(
				(PropertyHandler<SpannerPersistentProperty>) spannerPersistentProperty ->
						getCreateTableDdlStringsForHierarchy(
						spannerPersistentEntity.tableName(),
						spannerPersistentProperty.getColumnInnerType(), ddlStrings,
						seenClasses));
		seenClasses.add(entityClass);
	}

	private <T> void addPrimaryKeyColumnNames(
			SpannerPersistentEntity<T> spannerPersistentEntity, StringJoiner keyStrings) {
		for (SpannerPersistentProperty keyProp : spannerPersistentEntity
				.getPrimaryKeyProperties()) {
			if (keyProp.isEmbedded()) {
				addPrimaryKeyColumnNames(
						this.mappingContext.getPersistentEntity(keyProp.getType()),
						keyStrings);
			}
			else {
				keyStrings.add(keyProp.getColumnName());
			}
		}
	}

	private <T> void addColumnDdlStrings(
			SpannerPersistentEntity<T> spannerPersistentEntity,
			StringJoiner stringJoiner) {
		spannerPersistentEntity.doWithColumnBackedProperties(
				(PropertyHandler<SpannerPersistentProperty>) spannerPersistentProperty -> {
					if (spannerPersistentProperty.isEmbedded()) {
						addColumnDdlStrings(
								this.mappingContext.getPersistentEntity(
										spannerPersistentProperty.getType()),
								stringJoiner);
					}
					else {
						stringJoiner.add(getColumnDdlString(spannerPersistentProperty,
								this.spannerEntityProcessor));
					}
				});
	}

	private void getDropTableDdlStringsForHierarchy(Class entityClass,
			List<String> dropStrings, Set<Class> seenClasses) {
		if (seenClasses.contains(entityClass)) {
			return;
		}
		seenClasses.add(entityClass);
		dropStrings.add(0, getDropTableDdlString(entityClass));
		SpannerPersistentEntity spannerPersistentEntity = this.mappingContext
				.getPersistentEntity(entityClass);
		spannerPersistentEntity.doWithChildCollectionProperties(
				(PropertyHandler<SpannerPersistentProperty>) spannerPersistentProperty ->
						getDropTableDdlStringsForHierarchy(
						spannerPersistentProperty.getColumnInnerType(), dropStrings,
						seenClasses));
	}

	private String getTypeDdlString(Type.Code type, boolean isArray,
			OptionalLong dataLength) {
		Assert.notNull(type, "A valid Spanner column type is required.");
		if (isArray) {
			return "ARRAY<" + getTypeDdlString(type, false, dataLength) + ">";
		}
		return type.toString() + (type == Type.Code.STRING || type == Type.Code.BYTES
				? "(" + (dataLength.isPresent() ? dataLength.getAsLong() : "MAX") + ")"
				: "");
	}

}

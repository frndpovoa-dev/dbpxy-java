package com.dbpxy.langchain4j;

/*-
 * #%L
 * dbpxy-langchain4j-lib
 * %%
 * Copyright (C) 2025 - 2026 Fernando Lemes Povoa
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.*;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotEmpty;

/**
 * Handle Metadata stored in independent columns
 */
class ColumnsMetadataHandler implements MetadataHandler {

    final List<MetadataColumDefinition> columnsDefinition;
    final List<String> columnsName;
    final DbpxyFilterMapper filterMapper;
    final List<String> indexes;
    final String indexType;

    /**
     * MetadataHandler constructor
     * @param config {@link MetadataStorageConfig} configuration
     */
    public ColumnsMetadataHandler(MetadataStorageConfig config) {
        List<String> columnsDefinitionList = ensureNotEmpty(config.columnDefinitions(), "Metadata definition");
        this.columnsDefinition = columnsDefinitionList.stream()
                .map(MetadataColumDefinition::from).collect(Collectors.toList());
        this.columnsName = columnsDefinition.stream()
                .map(MetadataColumDefinition::getName).collect(Collectors.toList());
        this.filterMapper = new ColumnFilterMapper();
        this.indexes = getOrDefault(config.indexes(), Collections.emptyList());
        this.indexType = config.indexType();
    }

    @Override
    public String columnDefinitionsString() {
        return this.columnsDefinition.stream()
                .map(MetadataColumDefinition::getFullDefinition).collect(Collectors.joining(","));
    }

    @Override
    public List<String> columnsNames() {
        return this.columnsName;
    }

    @Override
    public void createMetadataIndexes(Statement statement, String table) {
        String indexTypeSql = indexType == null ? "" : "USING " + indexType;
        this.indexes.stream().map(String::trim)
                .forEach(index -> {
                    String indexSql = String.format("create index if not exists %s_%s on %s %s ( %s )",
                            table, index, table, indexTypeSql, index);
                    try {
                        statement.executeUpdate(indexSql);
                    } catch (SQLException e) {
                        throw new RuntimeException(String.format("Cannot create indexes %s: %s", index, e));
                    }
                });
    }

    @Override
    public String insertClause() {
        return this.columnsName.stream().map(c -> String.format("%s = EXCLUDED.%s", c, c))
                .collect(Collectors.joining(","));
    }

    @Override
    public void setMetadata(PreparedStatement upsertStmt, Integer parameterInitialIndex, Metadata metadata) {
        Map<String, Object> metadataMap = metadata.toMap();
        int i = 0;
        // only column names fields will be stored
        for (String columnName : this.columnsName) {
            try {
                String metadataValue = Objects.toString(metadataMap.get(columnName), null);
                upsertStmt.setObject(parameterInitialIndex + i, metadataValue, Types.OTHER);
                i++;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String whereClause(Filter filter) {
        return filterMapper.map(filter);
    }

    @Override
    public Metadata fromResultSet(ResultSet resultSet) {
        try {
            Map<String, Object> metadataMap = new HashMap<>();
            for (String c : this.columnsName) {
                if (resultSet.getObject(c) != null) {
                    metadataMap.put(c, resultSet.getObject(c));
                }
            }
            return new Metadata(metadataMap);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}

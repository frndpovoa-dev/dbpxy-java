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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Handle PV Store metadata.
 */
interface MetadataHandler {

    /**
     * String definition used to create the metadata field(s) in embeddings table
     *
     * @return the sql clause that creates metadata field(s)
     *
     */
    String columnDefinitionsString();

    /**
     * Setup indexes for metadata fields
     * By default, no index is created.
     *
     * @param statement used to execute indexes creation.
     * @param table table name.
     */
    void createMetadataIndexes(Statement statement, String table);

    /**
     * Metadata columns name
     *
     * @return list of columns used as metadata
     */
    List<String> columnsNames();

    /**
     * Generate the SQL where clause following @{@link Filter}
     *
     * @param filter filter
     * @return the sql where clause
     */
    String whereClause(Filter filter);

    /**
     * Extract Metadata from Resultset and Metadata definition
     *
     * @param resultSet resultSet
     * @return metadata object
     */
    Metadata fromResultSet(ResultSet resultSet);

    /**
     * Generate the SQL insert clause following Metadata definition
     *
     * @return the sql insert clause
     */
    String insertClause();

    /**
     * Set meta data values following metadata and metadata definition
     *
     * @param upsertStmt statement to set values
     * @param parameterInitialIndex initial parameter index
     * @param metadata metadata values
     */
    void setMetadata(PreparedStatement upsertStmt, Integer parameterInitialIndex, Metadata metadata);


}

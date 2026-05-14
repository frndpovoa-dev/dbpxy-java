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

import com.dbpxy.ConnectionHolder;
import com.dbpxy.util.UniqueIdGenerator;
import com.pgvector.PGvector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dev.langchain4j.internal.Utils.*;
import static dev.langchain4j.internal.ValidationUtils.*;
import static java.lang.String.join;
import static java.util.Collections.nCopies;
import static java.util.Collections.singletonList;

/**
 * DBPXY EmbeddingStore Implementation
 * <p>
 * Only cosine similarity is used.
 * Only ivfflat index is used.
 */
@Slf4j
public class DbpxyEmbeddingStore implements EmbeddingStore<TextSegment> {

    /**
     * Search modes for the embedding store.
     */
    public enum SearchMode {
        VECTOR,
        HYBRID
    }

    private static final String DEFAULT_TEXT_SEARCH_CONFIG = "simple";
    /**
     * Default {@code k} parameter used by the Reciprocal Rank Fusion (RRF) algorithm when
     * combining embedding and full-text search rankings.
     */
    private static final int DEFAULT_RRF_K = 60;

    private final UniqueIdGenerator uniqueIdGenerator;
    private final ConnectionHolder connectionHolder;

    /**
     * Embeddings table name
     */
    private final String tableName;

    /**
     * Flag to do not execute the {@code CREATE VECTOR EXTENSION} when retrieving a DBPXY connection
     */
    private final boolean skipCreateVectorExtension;

    /**
     * Metadata handler
     */
    private final MetadataHandler metadataHandler;

    /**
     * Search mode
     */
    private final SearchMode searchMode;

    /**
     * PostgreSQL text search configuration to use for full-text search operations,
     * such as determining language-specific parsing and stemming.
     */
    private final String textSearchConfig;

    /**
     * RRF k parameter (instance-level, configurable via builder). If null, DEFAULT_RRF_K used.
     */
    private final int rrfK;

    /**
     * New constructor that takes the DatasourceBuilder.
     * This is the entry point for enhanced configuration (searchMode, textSearchConfig, rrfK and skipCreateVectorExtension).
     *
     * @param builder The builder containing all configuration
     */
    protected DbpxyEmbeddingStore(final DatasourceArgs builder) {
        super();
        this.uniqueIdGenerator = builder.uniqueIdGenerator;
        this.connectionHolder = builder.connectionHolder;
        this.tableName = ensureNotBlank(builder.tableName, "ragcontent");
        MetadataStorageConfig config = getOrDefault(builder.metadataStorageConfig, DefaultMetadataStorageConfig.defaultConfig());
        this.metadataHandler = MetadataHandlerFactory.get(config);
        this.skipCreateVectorExtension = getOrDefault(builder.skipCreateVectorExtension, false);
        this.searchMode = getOrDefault(builder.searchMode, SearchMode.VECTOR);
        this.textSearchConfig = getOrDefault(builder.textSearchConfig, DEFAULT_TEXT_SEARCH_CONFIG);
        this.rrfK = ensureGreaterThanZero(getOrDefault(builder.rrfK, DEFAULT_RRF_K), "rrfK");
    }

    /**
     * Adds a given embedding to the store.
     *
     * @param embedding The embedding to be added to the store.
     * @return The auto-generated ID associated with the added embedding.
     */
    @Override
    public String add(final Embedding embedding) {
        final String id = uniqueIdGenerator.globalUUID(DbpxyEmbeddingStore.class.getName());
        addInternal(id, embedding, null);
        return id;
    }

    /**
     * Adds a given embedding to the store.
     *
     * @param id        The unique identifier for the embedding to be added.
     * @param embedding The embedding to be added to the store.
     */
    @Override
    public void add(
            final String id,
            final Embedding embedding) {
        addInternal(id, embedding, null);
    }

    /**
     * Adds a given embedding and the corresponding content that has been embedded to the store.
     *
     * @param embedding   The embedding to be added to the store.
     * @param textSegment Original content that was embedded.
     * @return The auto-generated ID associated with the added embedding.
     */
    @Override
    public String add(
            final Embedding embedding,
            final TextSegment textSegment) {
        final String id = uniqueIdGenerator.globalUUID(DbpxyEmbeddingStore.class.getName());
        addInternal(id, embedding, textSegment);
        return id;
    }

    /**
     * Adds multiple embeddings to the store.
     *
     * @param embeddings A list of embeddings to be added to the store.
     * @return A list of auto-generated IDs associated with the added embeddings.
     */
    @Override
    public List<String> addAll(
            final List<Embedding> embeddings) {
        final List<String> ids = embeddings.stream()
                .map(ignored -> uniqueIdGenerator.globalUUID(DbpxyEmbeddingStore.class.getName()))
                .collect(Collectors.toList());
        addAll(ids, embeddings, null);
        return ids;
    }

    @Override
    public void removeAll(final Collection<String> ids) {
        ensureNotEmpty(ids, "ids");
        final String sql = String.format("DELETE FROM %s WHERE embedding_id = ANY (?)", tableName);
        try (final Connection connection = getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {
            java.sql.Array array = connection.createArrayOf(
                    "varchar", ids.toArray());
            statement.setArray(1, array);
            statement.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeAll(final Filter filter) {
        ensureNotNull(filter, "filter");
        final String whereClause = metadataHandler.whereClause(filter);
        final String sql = String.format("DELETE FROM %s WHERE %s", tableName, whereClause);
        try (final Connection connection = getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeAll() {
        try (final Connection connection = getConnection();
             final Statement statement = connection.createStatement()) {
            statement.executeUpdate(String.format("TRUNCATE TABLE %s", tableName));
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Searches for the most similar (closest in the embedding space) {@link Embedding}s.
     * <br>
     * All search criteria are defined inside the {@link EmbeddingSearchRequest}.
     * <br>
     * {@link EmbeddingSearchRequest#filter()} is used to filter by meta dada.
     *
     * @param request A request to search in an {@link EmbeddingStore}. Contains all search criteria.
     * @return An {@link EmbeddingSearchResult} containing all found {@link Embedding}s.
     */
    @Override
    public EmbeddingSearchResult<TextSegment> search(final EmbeddingSearchRequest request) {
        final SearchMode mode = getOrDefault(searchMode, SearchMode.VECTOR);

        switch (mode) {
            case VECTOR: return embeddingOnlySearch(request);
            default: return hybridSearch(request);
        }
    }

    private EmbeddingSearchResult<TextSegment> embeddingOnlySearch(final EmbeddingSearchRequest request) {
        final Embedding referenceEmbedding = request.queryEmbedding();
        final int maxResults = request.maxResults();
        final double minScore = request.minScore();
        final Filter filter = request.filter();

        final List<EmbeddingMatch<TextSegment>> result = new ArrayList<>();
        try (final Connection connection = getConnection()) {
            final String referenceVector = Arrays.toString(referenceEmbedding.vector());
            String whereClause = (filter == null) ? "" : metadataHandler.whereClause(filter);
            whereClause = (whereClause.isEmpty()) ? "" : "AND " + whereClause;
            String query = String.format(
                    "SELECT (2 - (embedding <=> '%s')) / 2 AS score, embedding_id, embedding, text, %s FROM %s "
                            + "WHERE round(cast(float8 (embedding <=> '%s') as numeric), 8) <= round(2 - 2 * %s, 8) %s "
                            + "ORDER BY embedding <=> '%s' LIMIT %s;",
                    referenceVector,
                    join(",", metadataHandler.columnsNames()),
                    tableName,
                    referenceVector,
                    minScore,
                    whereClause,
                    referenceVector,
                    maxResults);
            try (final PreparedStatement selectStmt = connection.prepareStatement(query)) {
                try (final ResultSet resultSet = selectStmt.executeQuery()) {
                    while (resultSet.next()) {
                        final double score = resultSet.getDouble("score");
                        final String embeddingId = resultSet.getString("embedding_id");

                        final PGvector vector = (PGvector) resultSet.getObject("embedding");
                        final Embedding embedding = new Embedding(vector.toArray());

                        final String text = resultSet.getString("text");
                        TextSegment textSegment = null;
                        if (isNotNullOrBlank(text)) {
                            final Metadata metadata = metadataHandler.fromResultSet(resultSet);
                            textSegment = TextSegment.from(text, metadata);
                        }
                        result.add(new EmbeddingMatch<>(score, embeddingId, embedding, textSegment));
                    }
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
        return new EmbeddingSearchResult<>(result);
    }

    private EmbeddingSearchResult<TextSegment> hybridSearch(final EmbeddingSearchRequest request) {
        final Embedding referenceEmbedding = request.queryEmbedding();
        final String keywordQuery = request.query();

        if (isNullOrBlank(keywordQuery)) {
            throw new RuntimeException(
                    "For HYBRID search mode, the query must be provided in the EmbeddingSearchRequest");
        }

        final int maxResults = request.maxResults();
        final double minScore = request.minScore();
        final Filter filter = request.filter();

        final List<EmbeddingMatch<TextSegment>> result = new ArrayList<>();

        try (final Connection connection = getConnection()) {
            final String referenceVector = Arrays.toString(referenceEmbedding.vector());

            final String filterCondition = (filter == null) ? "" : metadataHandler.whereClause(filter);
            final String vectorWhere = filterCondition.isEmpty() ? "" : "WHERE " + filterCondition;
            final String keywordWhere = filterCondition.isEmpty() ? "" : " AND " + filterCondition;

            final List<String> metadataCols = metadataHandler.columnsNames();
            final String rawMetadataCols = metadataCols.isEmpty() ? "" : ", " + String.join(", ", metadataCols);

            String coalescedMetadataCols = "";
            if (!metadataCols.isEmpty()) {
                coalescedMetadataCols = ", "
                        + metadataCols.stream()
                        .map(col -> String.format("COALESCE(v.%1$s, k.%1$s) AS %1$s", col))
                        .collect(java.util.stream.Collectors.joining(", "));
            }

            final String sql = String.format(
                            " WITH vector_search AS ("+
                              " SELECT"+
                                " embedding_id, embedding, text %1$s,"+
                                " RANK() OVER (ORDER BY embedding <=> '%2$s') AS rnk"+
                              " FROM %3$s"+
                              " %4$s"+
                              " ORDER BY embedding <=> '%2$s'"+
                              " LIMIT %5$d"+
                            " ), keyword_search AS ("+
                              " SELECT"+
                                " embedding_id, embedding, text %1$s,"+
                                " RANK() OVER (ORDER BY ts_rank(to_tsvector('%6$s', coalesce(text, '')), plainto_tsquery('%6$s', ?)) DESC) AS rnk"+
                              " FROM %3$s"+
                              " WHERE to_tsvector('%6$s', coalesce(text, '')) @@ plainto_tsquery('%6$s', ?)"+
                                " %7$s"+
                              " ORDER BY ts_rank(to_tsvector('%6$s', coalesce(text, '')), plainto_tsquery('%6$s', ?)) DESC"+
                              " LIMIT %5$d"+
                            " )"+
                            " SELECT * FROM ("+
                              " SELECT"+
                                " COALESCE(v.embedding_id, k.embedding_id) AS embedding_id,"+
                                " COALESCE(v.embedding, k.embedding) AS embedding,"+
                                " COALESCE(v.text, k.text) AS text"+
                                " %8$s,"+
                                " COALESCE(1.0 / (%9$d + v.rnk), 0.0) + COALESCE(1.0 / (%9$d + k.rnk), 0.0) AS score"+
                              " FROM vector_search v"+
                              " FULL OUTER JOIN keyword_search k ON v.embedding_id = k.embedding_id"+
                            " ) ranked"+
                            " WHERE ranked.score >= ?"+
                            " ORDER BY ranked.score DESC"+
                            " LIMIT %10$d;",
                    rawMetadataCols,
                    referenceVector,
                    tableName,
                    vectorWhere,
                    Math.max(maxResults, rrfK),
                    textSearchConfig,
                    keywordWhere,
                    coalescedMetadataCols,
                    rrfK,
                    maxResults);

            try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, keywordQuery);
                stmt.setString(2, keywordQuery);
                stmt.setString(3, keywordQuery);
                stmt.setDouble(4, minScore);

                try (final ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        final double score = rs.getDouble("score");
                        final String embeddingId = rs.getString("embedding_id");

                        final PGvector vector = (PGvector) rs.getObject("embedding");
                        final Embedding embedding = new Embedding(vector.toArray());

                        final String text = rs.getString("text");
                        TextSegment textSegment = null;
                        if (isNotNullOrBlank(text)) {
                            final Metadata metadata = metadataHandler.fromResultSet(rs);
                            textSegment = TextSegment.from(text, metadata);
                        }
                        result.add(new EmbeddingMatch<>(score, embeddingId, embedding, textSegment));
                    }
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }

        return new EmbeddingSearchResult<>(result);
    }

    private void addInternal(
            final String id,
            final Embedding embedding,
            final TextSegment embedded) {
        addAll(singletonList(id), singletonList(embedding), embedded == null ? null : singletonList(embedded));
    }

    @Override
    public void addAll(
            final List<String> ids,
            final List<Embedding> embeddings,
            final List<TextSegment> embedded) {
        if (isNullOrEmpty(ids) || isNullOrEmpty(embeddings)) {
            log.info("Empty embeddings - no ops");
            return;
        }
        ensureTrue(ids.size() == embeddings.size(), "ids size is not equal to embeddings size");
        ensureTrue(
                embedded == null || embeddings.size() == embedded.size(),
                "embeddings size is not equal to embedded size");

        try (final Connection connection = getConnection()) {
            String query = String.format(
                    "INSERT INTO %s (embedding_id, embedding, text, %s) VALUES (?, ?, ?, %s)"
                            + "ON CONFLICT (embedding_id) DO UPDATE SET "
                            + "embedding = EXCLUDED.embedding,"
                            + "text = EXCLUDED.text,"
                            + "%s;",
                    tableName,
                    join(",", metadataHandler.columnsNames()),
                    join(",", nCopies(metadataHandler.columnsNames().size(), "?")),
                    metadataHandler.insertClause());
            try (final PreparedStatement upsertStmt = connection.prepareStatement(query)) {
                for (int i = 0; i < ids.size(); ++i) {
                    upsertStmt.setObject(1, ids.get(i));
                    upsertStmt.setObject(2, new PGvector(embeddings.get(i).vector()));

                    if (embedded != null && embedded.get(i) != null) {
                        upsertStmt.setObject(3, embedded.get(i).text());
                        metadataHandler.setMetadata(
                                upsertStmt, 4, embedded.get(i).metadata());
                    } else {
                        upsertStmt.setNull(3, Types.VARCHAR);
                        IntStream.range(4, 4 + metadataHandler.columnsNames().size())
                                .forEach(j -> {
                                    try {
                                        upsertStmt.setNull(j, Types.OTHER);
                                    } catch (SQLException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                    }
                    upsertStmt.addBatch();
                }
                upsertStmt.executeBatch();
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Datasource connection
     * Creates the vector extension and add the vector type if it does not exist.
     * Could be overridden in case extension creation and adding type is done at datasource initialization step.
     *
     * @return Datasource connection
     * @throws SQLException exception
     */
    protected Connection getConnection() throws SQLException {
        final Connection connection = new Connection(connectionHolder.getConnection());
        if (connection == null) {
            throw new SQLException();
        }
        // Find a way to do the following code in connection initialization.
        // Here we assume the datasource could handle a connection pool
        // and we should add the vector type on each connection
        if (!skipCreateVectorExtension) {
            try (final Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS vector");
            }
        }
        PGvector.addVectorType(connection);
        return connection;
    }

    @RequiredArgsConstructor
    public static class Connection implements java.sql.Connection, AutoCloseable {
        @Delegate(types = java.sql.Connection.class)
        private final com.dbpxy.jdbc.Connection connection;

        @Override
        public void close() {
            // Do nothing
        }
    }

    @Builder
    @ToString
    public static class DatasourceArgs {
        private final UniqueIdGenerator uniqueIdGenerator;
        private final ConnectionHolder connectionHolder;
        private final String tableName;
        private final Boolean skipCreateVectorExtension;
        private final MetadataStorageConfig metadataStorageConfig;
        private final SearchMode searchMode;
        private final String textSearchConfig;
        private final Integer rrfK;

        public DbpxyEmbeddingStore buildEmbeddingStore() {
            return new DbpxyEmbeddingStore(this);
        }
    }
}

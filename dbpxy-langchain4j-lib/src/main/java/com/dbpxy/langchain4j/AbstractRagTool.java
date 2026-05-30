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
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

public abstract class AbstractRagTool {
    private final EmbeddingModel embeddingModel;
    private final DbpxyEmbeddingStore embeddingStore;

    protected AbstractRagTool(
            final ConnectionHolder connectionHolder,
            final EmbeddingModel embeddingModel,
            final String tableName) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = DbpxyEmbeddingStore.DatasourceArgs.builder()
                .connectionHolder(connectionHolder)
                .tableName(tableName)
                .searchMode(DbpxyEmbeddingStore.SearchMode.HYBRID)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COMBINED_JSON)
                        .columnDefinitions(List.of(
                                "created VARCHAR(50) NOT NULL",
                                "meeting VARCHAR(255) NOT NULL"))
                        .build())
                .build()
                .buildEmbeddingStore();
    }

    protected List<String> retrieve(
            @NonNull final OffsetDateTime time,
            @NonNull final String meeting,
            @NonNull final String query,
            final int maxResults) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .filter(
                        metadataKey("created").isGreaterThan(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time))
                                .and(metadataKey("meeting").isEqualTo(meeting)))
                .build()
                .retrieve(Query.from(query)).stream()
                .map(Content::textSegment)
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }

    protected void save(
            @NonNull final OffsetDateTime time,
            @NonNull final String meeting,
            @NonNull final String content) {
        final TextSegment segment = TextSegment.from(
                content,
                new Metadata(Map.of(
                        "created", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time),
                        "meeting", meeting)));
        final Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }
}

package com.dbpxy.util;

/*-
 * #%L
 * dbpxy-lib
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

import com.dbpxy.proto.Transaction;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@UtilityClass
public class TransactionUtils {
    private static final String SCHEME = "dbpxy";
    private static final String CREATION_PARAM = "iat";
    private static final String EXPIRATION_PARAM = "exp";
    private static final String FORWARD_SLASH_CHAR = "/";
    private static final String AMPERSAND_CHAR = "&";
    private static final String EQUAL_CHAR = "=";

    private enum Permission {
        FULL, READ_ONLY, READ_WRITE;
    }

    public static @NonNull String format(@NonNull final Transaction transaction) throws URISyntaxException {
        return format(transaction, Permission.FULL);
    }

    public static @NonNull String formatToReadOnly(@NonNull final Transaction transaction) throws URISyntaxException {
        return format(transaction, Permission.READ_ONLY);
    }

    public static @NonNull String formatToReadWrite(@NonNull final Transaction transaction) throws URISyntaxException {
        return format(transaction, Permission.READ_WRITE);
    }

    private static @NonNull String format(
            @NonNull final Transaction transaction,
            @NonNull final Permission permission) throws URISyntaxException {
        String id;
        switch (permission) {
            case FULL -> id = transaction.getId();
            case READ_WRITE -> id = transaction.getReadWriteId();
            default -> id = transaction.getReadOnlyId();
        }
        final String query = Map.of(
                        CREATION_PARAM, transaction.getCreation(),
                        EXPIRATION_PARAM, transaction.getExpiration()
                ).entrySet().stream()
                .filter(entry -> StringUtils.isNotEmpty(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> URLEncoder.encode(entry.getKey(), UTF_8) + EQUAL_CHAR + URLEncoder.encode(entry.getValue(), UTF_8))
                .collect(Collectors.joining(AMPERSAND_CHAR));
        final URI uri = new URI(SCHEME, null, transaction.getNode(), -1, FORWARD_SLASH_CHAR + URLEncoder.encode(id, UTF_8), query, "");
        return Base64.getEncoder().encodeToString(uri.toString().getBytes(UTF_8));
    }

    private static @Nullable Transaction tryParseBasic(@NonNull final URI transactionUri) {
        try {
            return Transaction.newBuilder()
                    .setId(URLDecoder.decode(transactionUri.getPath().substring(1), UTF_8))
                    .setNode(transactionUri.getHost())
                    .build();
        } catch (final RuntimeException e) {
            log.debug(e.getMessage(), e);
            return null;
        }
    }

    public static @Nullable Transaction tryParseBasic(@Nullable final String transactionId) {
        if (transactionId == null) {
            return null;
        }
        try {
            final URI transactionUri = URI.create(new String(Base64.getDecoder().decode(transactionId), UTF_8));
            return tryParseBasic(transactionUri);
        } catch (final RuntimeException e) {
            log.debug(e.getMessage(), e);
            return null;
        }
    }

    public static @Nullable Transaction tryParse(@Nullable final String transactionId) {
        if (transactionId == null) {
            return null;
        }
        try {
            final URI transactionUri = URI.create(new String(Base64.getDecoder().decode(transactionId), UTF_8));
            final Map<String, String> queryParams = new LinkedHashMap<>(StringUtils.countMatches(transactionUri.getQuery(), EQUAL_CHAR));
            for (final String pair : transactionUri.getQuery().split(AMPERSAND_CHAR)) {
                final int idx = pair.indexOf(EQUAL_CHAR);
                final String key = URLDecoder.decode(pair.substring(0, idx), UTF_8);
                final String value = URLDecoder.decode(pair.substring(idx + 1), UTF_8);
                queryParams.put(key, value);
            }
            return Objects.requireNonNull(tryParseBasic(transactionUri)).toBuilder()
                    .setCreation(queryParams.getOrDefault(CREATION_PARAM, ""))
                    .setExpiration(queryParams.getOrDefault(EXPIRATION_PARAM, ""))
                    .build();
        } catch (final RuntimeException e) {
            log.debug(e.getMessage(), e);
            return null;
        }
    }
}

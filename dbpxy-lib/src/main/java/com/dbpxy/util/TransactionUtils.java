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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

@UtilityClass
public class TransactionUtils {

    public static String format(final Transaction transaction) throws URISyntaxException {
        final String query = Map.of(
                        "iat", transaction.getCreation(),
                        "exp", transaction.getExpiration()
                ).entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), UTF_8) + "=" + URLEncoder.encode(entry.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
        final URI uri = new URI("dbpxy", null, transaction.getNode(), -1, "/" + URLEncoder.encode(transaction.getId(), UTF_8), query, "");
        return Base64.getEncoder().encodeToString(uri.toString().getBytes(UTF_8));
    }

    public static Transaction parse(final String transactionId) {
        final URI transactionUri = URI.create(new String(Base64.getDecoder().decode(transactionId), UTF_8));
        final Map<String, String> queryParams = new HashMap<>();
        for (final String pair : transactionUri.getQuery().split("&")) {
            final int idx = pair.indexOf("=");
            final String key = URLDecoder.decode(pair.substring(0, idx), UTF_8);
            final String value = URLDecoder.decode(pair.substring(idx + 1), UTF_8);
            queryParams.put(key, value);
        }
        return Transaction.newBuilder()
                .setId(URLDecoder.decode(transactionUri.getPath().substring(1), UTF_8))
                .setNode(transactionUri.getHost())
                .setCreation(queryParams.get("iat"))
                .setExpiration(queryParams.get("exp"))
                .build();
    }
}

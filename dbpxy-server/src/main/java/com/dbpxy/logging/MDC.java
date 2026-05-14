package com.dbpxy.logging;

/*-
 * #%L
 * dbpxy
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
import com.dbpxy.util.DatabaseUtils;

public class MDC implements AutoCloseable {
    private final String key;

    public MDC(
            final String key,
            final String value) {
        this.key = key;
        org.slf4j.MDC.put(key, value);
    }

    public MDC(
            final String key,
            final Transaction transaction) {
        this(key, DatabaseUtils.getMaskedId(transaction.getId()));
    }

    @Override
    public void close() {
        org.slf4j.MDC.remove(key);
    }
}

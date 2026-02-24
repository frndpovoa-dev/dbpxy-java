package com.dbpxy.service;

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

class DatabaseUtil {
    public static String getMaskedId(final String id) {
        return id.substring(0, 32);
    }

    public static int sanitizeFetchSize(final long fetchSize) {
        return Math.clamp(fetchSize, 25, Integer.MAX_VALUE);
    }

    public static int sanitizeTimeout(final long timeout) {
        return Math.clamp(timeout, 0, Integer.MAX_VALUE);
    }
}

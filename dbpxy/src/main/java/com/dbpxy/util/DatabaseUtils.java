package com.dbpxy.util;

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

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

import java.time.Duration;

@UtilityClass
public class DatabaseUtils {
    public static String getMaskedId(@NonNull final String id) {
        return id.substring(0, Math.min(20, id.length()));
    }

    public static int sanitizeFetchSize(final long fetchSize) {
        // TODO: Make it configurable
        return Math.clamp(fetchSize, 25, 1_000);
    }

    public static int sanitizeTimeout(final long timeoutInMs) {
        return Math.clamp(Duration.ofMillis(timeoutInMs).toSeconds(), 0, Integer.MAX_VALUE);
    }

    public static int sanitizeTimeoutInMs(final long timeoutInMs) {
        return Math.clamp(timeoutInMs, 0, Integer.MAX_VALUE);
    }
}

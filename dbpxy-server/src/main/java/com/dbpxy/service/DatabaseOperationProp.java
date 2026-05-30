package com.dbpxy.service;

/*-
 * #%L
 * dbpxy-server
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

import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Getter
@Builder(toBuilder = true)
public class DatabaseOperationProp {
    private final long timeoutInMs;
    private final boolean autoCommit;
    private final boolean readOnly;
    private final ConnectionString connectionString;
    private final Activation activation;

    public enum Activation {
        EAGER,
        LAZY,
        ;
    }

    @Getter
    @Builder
    public static class ConnectionString {
        private final char[] url;
        private final List<ConnectionStringProp> props;

        public @Nullable String getUrl() {
            return (url == null) ? null : String.valueOf(url);
        }
    }

    @Builder
    public static class ConnectionStringProp {
        private final char[] name;
        private final char[] value;

        public @Nullable String getName() {
            return (name == null) ? null : String.valueOf(name);
        }

        public @Nullable String getValue() {
            return (value == null) ? null : String.valueOf(value);
        }
    }
}

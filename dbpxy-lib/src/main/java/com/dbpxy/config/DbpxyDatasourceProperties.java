package com.dbpxy.config;

/*-
 * #%L
 * dbpxy-lib
 * %%
 * Copyright (C) 2025 Fernando Lemes Povoa
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

import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties(prefix = "app.dbpxy-datasource")
public class DbpxyDatasourceProperties {
    private char[] url;
    @Builder.Default
    private Activation activation = Activation.LAZY;
    @Builder.Default
    private Database database = Database.H2;
    @Builder.Default
    private List<Prop> props = new ArrayList<>(0);

    public void setUrl(@Nullable final String url) {
        this.url = (url == null) ? null : url.toCharArray();
    }

    public @Nullable String getUrl() {
        return (url == null) ? null : String.valueOf(url);
    }

    public enum Activation {
        EAGER,
        LAZY,
        ;
    }

    public enum Database {
        H2,
        ORACLE,
        POSTGRESQL,
        ;
    }

    public static class Prop {
        private char[] name;
        private char[] value;

        public void setName(@Nullable final String name) {
            this.name = (name == null) ? null : name.toCharArray();
        }

        public @Nullable String getName() {
            return (name == null) ? null : String.valueOf(name);
        }

        public void setValue(@Nullable final String value) {
            this.value = (value == null) ? null : value.toCharArray();
        }

        public @Nullable String getValue() {
            return (value == null) ? null : String.valueOf(value);
        }
    }
}

package com.dbpxy.hint;

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

import org.jspecify.annotations.NonNull;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

public class CaffeineRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(
            @NonNull final RuntimeHints hints,
            final ClassLoader classLoader) {
        final List<String> list = List.of(
                "com.github.benmanes.caffeine.cache.SSLA",
                "com.github.benmanes.caffeine.cache.PSA",
                "com.github.benmanes.caffeine.cache.PSW",
                "com.github.benmanes.caffeine.cache.PSWMS",
                "com.github.benmanes.caffeine.cache.SSW",
                "com.github.benmanes.caffeine.cache.SSMS",
                "com.github.benmanes.caffeine.cache.SSLMS",
                "com.github.benmanes.caffeine.cache.BoundedLocalCache"
        );
        for (final String className : list) {
            hints.reflection()
                    .registerType(TypeReference.of(className), hint -> hint
                            .withMembers(
                                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                                    MemberCategory.INVOKE_DECLARED_METHODS,
                                    MemberCategory.ACCESS_DECLARED_FIELDS
                            )
                    );
        }
    }
}

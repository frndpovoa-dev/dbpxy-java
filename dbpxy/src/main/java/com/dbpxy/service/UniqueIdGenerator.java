package com.dbpxy.service;

/*-
 * #%L
 * dbpxy
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

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.UUIDUtil;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.Hashing;
import lombok.NonNull;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.util.UUID;

import static java.time.temporal.ChronoField.*;

@Service
public class UniqueIdGenerator {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(YEAR, 4, 4, SignStyle.EXCEEDS_PAD)
            .appendValue(MONTH_OF_YEAR, 2)
            .appendValue(DAY_OF_MONTH, 2)
            .appendValue(HOUR_OF_DAY, 2)
            .appendValue(MINUTE_OF_HOUR, 2)
            .appendValue(SECOND_OF_MINUTE, 2)
            .appendFraction(NANO_OF_SECOND, 9, 9, false)
            .toFormatter();
    private static final LoadingCache<String, String> HASHING_CACHE = CacheBuilder.newBuilder()
            .expireAfterAccess(Duration.ofDays(1))
            .build(new CacheLoader<>() {
                @Override
                public String load(final String groupName) {
                    return Hashing.sha256()
                            .hashString(groupName, StandardCharsets.UTF_8)
                            .toString()
                            .substring(0, 7);
                }
            });

    public String globalUUID(@NonNull final String groupName) {
        return compactUUID()
                + DATE_TIME_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC))
                + HASHING_CACHE.getUnchecked(groupName);
    }

    public String compactUUID() {
        final UUID uuid = Generators.randomBasedGenerator().generate();
        final StringBuilder sb = new StringBuilder(32);
        for (final byte b : UUIDUtil.asByteArray(uuid)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

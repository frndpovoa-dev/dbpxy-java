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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DatabaseOperationTest {

    @Test
    void testTimezoneOffset() {
        final TimeZone timezone = TimeZone.getDefault();

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertThat(new Timestamp(126, 5, 25, 22, 34, 0, 0).getTimezoneOffset())
                .isEqualTo(0);

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertThat(new Timestamp(126, 5, 25, 22, 34, 0, 0).getTimezoneOffset())
                .isEqualTo(240);

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"));
        assertThat(new Timestamp(126, 5, 25, 22, 34, 0, 0).getTimezoneOffset())
                .isEqualTo(-240);

        TimeZone.setDefault(timezone);
    }
}

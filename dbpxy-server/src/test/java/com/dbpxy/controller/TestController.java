package com.dbpxy.controller;

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

import com.dbpxy.bo.TestBo;
import com.dbpxy.config.Headers;
import com.dbpxy.exception.UnsupportedInReadOnlyModeException;
import com.dbpxy.service.TestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@RestController
@RequestMapping(path = "/api/v1/test")
@RequiredArgsConstructor
public class TestController {
    private final TestService service;

    @GetMapping(path = "/list")
    public List<TestBo> list(
            @RequestHeader(value = Headers.TRANSACTION, required = false) final String transactionId,
            @RequestParam("group") String groupName
    ) throws Exception {
        return service.list(transactionId, groupName);
    }

    @PostMapping(path = "/insert")
    public TestBo insert(
            @RequestHeader(value = Headers.TRANSACTION, required = false) final String transactionId,
            @RequestBody final TestBo testBo
    ) throws Exception {
        assertThat(service.findById(testBo.getId() + 1))
                .isEmpty();

        assertThat(service.findById(testBo.getId()))
                .isEmpty();

        service.save(transactionId, testBo);

        assertThat(service.findById(testBo.getId()))
                .isEmpty();

        service.save(testBo.toBuilder()
                .id(testBo.getId() + 1)
                .name(testBo.getName() + " from server side")
                .booleanValue(true)
                .byteValue((byte) (testBo.getByteValue() + 1))
                .shortValue((short) (testBo.getShortValue() + 1))
                .integerValue(testBo.getIntegerValue() + 1)
                .longValue(testBo.getLongValue() + 1L)
                .floatValue(testBo.getFloatValue() + 1.0F)
                .doubleValue(testBo.getDoubleValue() + 1)
                .bytesValue(new String(testBo.getBytesValue(), StandardCharsets.UTF_8)
                        .concat(" from server side")
                        .getBytes(StandardCharsets.UTF_8))
                .bigdecimalValue(testBo.getBigdecimalValue().add(BigDecimal.ONE))
                .sqlDateValue(testBo.getSqlDateValue())
                .sqlTimeValue(testBo.getSqlTimeValue())
                .sqlTimestampValue(testBo.getSqlTimestampValue())
                .utilDateValue(testBo.getUtilDateValue())
                .localDateValue(testBo.getLocalDateValue())
                .localTimeValue(testBo.getLocalTimeValue())
                .offsetDateTimeValue(testBo.getOffsetDateTimeValue())
                .build());

        assertThat(service.findById(testBo.getId() + 1))
                .isPresent();

        return testBo;
    }

    @PostMapping(path = "/try-insert")
    public TestBo safeInsert(
            @RequestHeader(value = Headers.TRANSACTION, required = false) final String transactionId,
            @RequestBody final TestBo testBo
    ) throws Exception {
        assertThat(service.findById(testBo.getId() + 1))
                .isEmpty();

        assertThat(service.findById(testBo.getId()))
                .isEmpty();

        assertThat(service.findById(transactionId, testBo.getId()))
                .isEmpty();

        try {
            service.save(transactionId, testBo);
        } catch (final UnsupportedInReadOnlyModeException e) {
            log.warn(e.getMessage(), e);
        }

        assertThat(service.findById(transactionId, testBo.getId()))
                .isEmpty();

        assertThat(service.findById(testBo.getId()))
                .isEmpty();

        service.save(testBo.toBuilder()
                .id(testBo.getId() + 1)
                .name(testBo.getName() + " from server side")
                .build());

        assertThat(service.findById(testBo.getId() + 1))
                .isPresent();

        return testBo;
    }
}

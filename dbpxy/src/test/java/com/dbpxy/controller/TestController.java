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

import com.dbpxy.ConnectionHolder;
import com.dbpxy.bo.TestBo;
import com.dbpxy.config.Headers;
import com.dbpxy.repository.TestRepository;
import com.dbpxy.service.TestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@RestController
@RequestMapping(path = "/api/v1/test")
@RequiredArgsConstructor
public class TestController {
    private final TestService service;
    private final TestRepository repository;
    private final ConnectionHolder connectionHolder;

    @Transactional(readOnly = true, timeout = 10)
    @GetMapping(path = "/list")
    public List<TestBo> list(
            @RequestHeader(value = Headers.TRANSACTION, required = false) final String transactionId,
            @RequestParam("group") String groupName
    ) throws Exception {
        return connectionHolder.doWithSharedTransaction(transactionId,
                () -> repository.findByGroupName(groupName));
    }

    @Transactional(timeout = 10)
    @PostMapping(path = "/insert")
    public TestBo insert(
            @RequestHeader(value = Headers.TRANSACTION, required = false) final String transactionId,
            @RequestBody final TestBo testBo
    ) throws Exception {
        assertThat(repository.findById(testBo.getId()))
                .isEmpty();

        connectionHolder.doWithSharedTransaction(
                transactionId,
                () -> {
                    assertThat(repository.findById(testBo.getId()))
                            .isEmpty();
                    repository.save(testBo);
                    assertThat(repository.findById(testBo.getId()))
                            .isPresent();
                });

        assertThat(repository.findById(testBo.getId()))
                .isEmpty();

        service.save(testBo);
        return testBo;
    }
}

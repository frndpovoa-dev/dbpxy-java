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

import com.dbpxy.ConnectionHolder;
import com.dbpxy.annotation.ShareableTransaction;
import com.dbpxy.bo.TestBo;
import com.dbpxy.repository.TestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Service
@RequiredArgsConstructor
public class TestService {
    private final TestRepository repository;
    private final ConnectionHolder connectionHolder;

    @ShareableTransaction(readOnly = true, timeout = 10)
    public Optional<TestBo> findById(final Long id) {
        return repository.findById(id);
    }

    @ShareableTransaction(timeout = 10)
    public TestBo save(final TestBo testBo) {
        return repository.save(testBo);
    }

    @ShareableTransaction(readOnly = true, timeout = 10)
    public List<TestBo> list(
            final String transactionId,
            final String groupName) throws Exception {
        return connectionHolder.doWithSharedTransaction(transactionId,
                () -> repository.findByGroupName(groupName));
    }

    @ShareableTransaction(timeout = 10)
    public TestBo save(
            final String transactionId,
            final TestBo testBo) throws Exception {
        return connectionHolder.doWithSharedTransaction(
                transactionId,
                () -> {
                    assertThat(repository.findById(testBo.getId()))
                            .isEmpty();
                    final TestBo result = repository.save(testBo);
                    assertThat(repository.findById(testBo.getId()))
                            .isPresent();
                    return result;
                });
    }

    @ShareableTransaction(timeout = 10)
    public Optional<TestBo> findById(
            final String transactionId,
            final Long id) throws Exception {
        return connectionHolder.doWithSharedTransaction(
                transactionId,
                () -> {
                    return repository.findById(id);
                });
    }
}

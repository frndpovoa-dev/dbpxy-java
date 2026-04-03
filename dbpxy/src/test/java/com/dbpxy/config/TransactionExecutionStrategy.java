package com.dbpxy.config;

/*-
 * #%L
 * dbpxy
 * $Id:$
 * $HeadURL:$
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
import com.dbpxy.jdbc.Connection;
import com.dbpxy.proto.Transaction;
import com.dbpxy.util.TransactionUtils;
import graphql.ExecutionResult;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionStrategyParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(timeout = 60)
public class TransactionExecutionStrategy extends AsyncSerialExecutionStrategy {
    private final ConnectionHolder connectionHolder;

    @Override
    public CompletableFuture<ExecutionResult> execute(
            final ExecutionContext executionContext,
            final ExecutionStrategyParameters parameters
    ) {
        final String transactionId = executionContext.getGraphQLContext().get(Headers.TRANSACTION);

        if (StringUtils.isNotEmpty(transactionId)) {
            try {
                final Connection connection = connectionHolder.getConnection();

                final Transaction transaction = TransactionUtils.parse(transactionId);
                connection.joinSharedTransaction(transaction);

                return super.execute(executionContext, parameters)
                        .whenComplete((executionResult, throwable) -> {
                            try {
                                connection.leaveSharedTransaction(transaction);
                            } catch (final SQLException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (final SQLException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        return super.execute(executionContext, parameters);
    }
}

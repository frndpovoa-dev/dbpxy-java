package com.dbpxy.config;

import com.dbpxy.jdbc.Connection;
import com.dbpxy.jdbc.DataSource;
import graphql.ExecutionResult;
import graphql.execution.AsyncSerialExecutionStrategy;
import graphql.execution.ExecutionContext;
import graphql.execution.ExecutionStrategyParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(timeout = 60)
public class TransactionExecutionStrategy extends AsyncSerialExecutionStrategy {
    private final DataSource dataSource;

    @Override
    public CompletableFuture<ExecutionResult> execute(
            final ExecutionContext executionContext,
            final ExecutionStrategyParameters parameters
    ) {
        Connection connection = null;
        final String transactionId = executionContext.getGraphQLContext().get(Headers.TRANSACTION);

        if (StringUtils.isNotEmpty(transactionId)) {
            try {
                connection = (Connection) DataSourceUtils.getConnection(dataSource);
                connection.setAutoCommit(false);
                connection.joinSharedTransaction(transactionId);

                final Connection connection1 = connection;
                return super.execute(executionContext, parameters)
                        .whenComplete((executionResult, throwable) -> {
                            DataSourceUtils.releaseConnection(connection1, dataSource);
                        });
            } catch (final SQLException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        return super.execute(executionContext, parameters);
    }
}

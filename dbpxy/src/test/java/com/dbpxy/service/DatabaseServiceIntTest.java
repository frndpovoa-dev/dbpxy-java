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

import com.dbpxy.BaseIntTest;
import com.dbpxy.config.GrpcProperties;
import com.dbpxy.proto.*;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseServiceIntTest extends BaseIntTest {
    private static final List<Value> ARGS_ID_1 = List.of(Value.newBuilder()
            .setCode(ValueCode.INT64)
            .setData(ValueInt64.newBuilder().setValue(1L).build().toByteString())
            .build());
    private static final List<Value> ARGS_ID_2 = List.of(Value.newBuilder()
            .setCode(ValueCode.INT64)
            .setData(ValueInt64.newBuilder().setValue(2L).build().toByteString())
            .build());
    private static final List<Row> RESULTS_NAME_DUMMY = List.of(Row.newBuilder()
            .addCols(Value.newBuilder()
                    .setCode(ValueCode.STRING)
                    .setData(ValueString.newBuilder().setValue("dummy").build().toByteString())
                    .build()
            )
            .build());
    private static final List<Row> RESULTS_NAME_FOOBAR = List.of(Row.newBuilder()
            .addCols(Value.newBuilder()
                    .setCode(ValueCode.STRING)
                    .setData(ValueString.newBuilder().setValue("foobar").build().toByteString())
                    .build()
            )
            .build());
    private static final String SELECT_NAME_FROM_TEST_WHERE_ID = """
            select name from test
            where id = ?;
            """;
    private static final String INSERT_INTO_TEST_ID_NAME = """
            insert into test (
              id,
              name
            ) values (
              ?,
              ?
            );
            """;
    private static final String CREATE_TABLE_TEST = """
            create table test (
              id bigint primary key,
              name varchar
            );
            """;
    public static final String DROP_TABLE_IF_EXISTS_TEST = """
            drop table if exists test;
            """;

    @Autowired
    private GrpcProperties grpcProperties;
    private DbpxyGrpc.DbpxyBlockingStub databaseProxyServiceClient;

    @BeforeEach
    void setUp() throws Exception {
        this.databaseProxyServiceClient = DbpxyGrpc.newBlockingStub(ManagedChannelBuilder
                .forAddress("localhost", grpcProperties.getPort())
                .usePlaintext()
                .build());

        ddl(DROP_TABLE_IF_EXISTS_TEST);
        ddl(CREATE_TABLE_TEST);
    }

    @Test
    void givenTableIsEmpty_thenSelect_thenReturnEmpty() {
        query(0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, null);
    }

    @Test
    void givenCreateTable_thenInsert_thenSelect() {
        Transaction tx1 = beginTransaction(10_000);
        Transaction tx2 = beginTransaction(10_000);

        executeTx(tx1, 1, INSERT_INTO_TEST_ID_NAME, Stream.of(
                        new AbstractMap.SimpleEntry<>(ValueInt64.newBuilder().setValue(1).build(), ValueCode.INT64),
                        new AbstractMap.SimpleEntry<>(ValueString.newBuilder().setValue("dummy").build(), ValueCode.STRING)
                )
                .map(entry -> Value.newBuilder()
                        .setCode(entry.getValue())
                        .setData(entry.getKey().toByteString())
                        .build())
                .toList());
        queryTx(tx1, 1, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, RESULTS_NAME_DUMMY);
        queryTx(tx2, 0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, null);
        query(0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, null);

        commit(tx1, Transaction.Status.COMMITTED);

        executeTx(tx2, 1, INSERT_INTO_TEST_ID_NAME, Stream.of(
                        new AbstractMap.SimpleEntry<>(ValueInt64.newBuilder().setValue(2).build(), ValueCode.INT64),
                        new AbstractMap.SimpleEntry<>(ValueString.newBuilder().setValue("foobar").build(), ValueCode.STRING)
                )
                .map(entry -> Value.newBuilder()
                        .setCode(entry.getValue())
                        .setData(entry.getKey().toByteString())
                        .build())
                .toList());
        queryTx(tx2, 1, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_2, RESULTS_NAME_FOOBAR);
        queryTx(tx2, 1, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, RESULTS_NAME_DUMMY);
        query(1, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, RESULTS_NAME_DUMMY);

        rollback(tx2);

        Transaction tx3 = beginTransaction(5_000);
        queryTx(tx3, 1, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, RESULTS_NAME_DUMMY);
        queryTx(tx3, 0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_2, null);

        commit(tx3, Transaction.Status.COMMITTED);
    }

    @Test
    void givenCreateTable_thenInsert_thenTransactionTimeout() {
        Transaction tx1 = beginTransaction(100);
        executeTx(tx1, 1, INSERT_INTO_TEST_ID_NAME, Stream.of(
                        new AbstractMap.SimpleEntry<>(ValueInt64.newBuilder().setValue(1).build(), ValueCode.INT64),
                        new AbstractMap.SimpleEntry<>(ValueString.newBuilder().setValue("dummy").build(), ValueCode.STRING)
                )
                .map(entry -> Value.newBuilder()
                        .setCode(entry.getValue())
                        .setData(entry.getKey().toByteString())
                        .build())
                .toList());

        sleepUninterruptibly(Duration.ofMillis(1_000));

        tx1 = commit(tx1, Transaction.Status.UNKNOWN);

        Transaction tx1a = tx1;
        assertThatThrownBy(() -> queryTx(tx1a, 0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, null))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .hasMessage("UNKNOWN: Transaction not found");

        query(0, SELECT_NAME_FROM_TEST_WHERE_ID, ARGS_ID_1, null);
    }

    private Transaction beginTransaction(
            int timeout
    ) {
        ConnectionString connectionString = ConnectionString.newBuilder()
                .setUrl(dataSourceProperties.getUrl())
                .addAllProps(dataSourceProperties.getProps().entrySet().stream()
                        .map(e -> ConnectionStringProp.newBuilder()
                                .setName(e.getKey())
                                .setValue(e.getValue())
                                .build())
                        .toList())
                .build();
        Transaction transaction = databaseProxyServiceClient.beginTransaction(BeginTransactionConfig.newBuilder()
                .setConnectionString(connectionString)
                .setTimeout(timeout)
                .build());
        assertThat(transaction)
                .isNotNull()
                .hasFieldOrProperty("id")
                .hasFieldOrPropertyWithValue("status", Transaction.Status.ACTIVE);
        return transaction;
    }

    private void ddl(
            String sql
    ) {
        ConnectionString connectionString = ConnectionString.newBuilder()
                .setUrl(dataSourceProperties.getUrl())
                .addAllProps(dataSourceProperties.getProps().entrySet().stream()
                        .map(e -> ConnectionStringProp.newBuilder()
                                .setName(e.getKey())
                                .setValue(e.getValue())
                                .build())
                        .toList())
                .build();
        ExecuteResult ddlResult = databaseProxyServiceClient.execute(ExecuteConfig.newBuilder()
                .setConnectionString(connectionString)
                .setTimeout(100)
                .setQuery(sql)
                .build());
        assertThat(ddlResult)
                .isNotNull();
    }

    private void executeTx(
            Transaction transaction,
            int rowsAffected,
            String sql,
            Collection<Value> args
    ) {
        ExecuteResult insertResult = databaseProxyServiceClient.executeTx(ExecuteTxConfig.newBuilder()
                .setTransaction(transaction)
                .setExecuteConfig(ExecuteConfig.newBuilder()
                        .setTimeout(100)
                        .setQuery(sql)
                        .addAllArgs(args)
                        .build())
                .build());
        assertThat(insertResult)
                .isNotNull()
                .hasFieldOrPropertyWithValue("rowsAffected", rowsAffected);
    }

    private Transaction commit(
            Transaction transaction,
            Transaction.Status expectedStatus
    ) {
        transaction = databaseProxyServiceClient.commitTransaction(transaction);
        assertThat(transaction)
                .isNotNull()
                .hasFieldOrPropertyWithValue("status", expectedStatus);
        return transaction;
    }

    private Transaction rollback(
            Transaction transaction
    ) {
        transaction = databaseProxyServiceClient.rollbackTransaction(transaction);
        assertThat(transaction)
                .isNotNull()
                .hasFieldOrPropertyWithValue("status", Transaction.Status.ROLLED_BACK);
        return transaction;
    }

    private void queryTx(
            Transaction transaction,
            int rowsReturned,
            String sql,
            List<Value> args,
            List<Row> expectedResults
    ) {
        QueryResult queryResult = databaseProxyServiceClient.queryTx(QueryTxConfig.newBuilder()
                .setTransaction(transaction)
                .setQueryConfig(QueryConfig.newBuilder()
                        .setTimeout(1_000)
                        .setQuery(sql)
                        .addAllArgs(args)
                        .build())
                .build());
        assertThat(queryResult)
                .isNotNull();
        if (rowsReturned > 0) {
            assertThat(queryResult.getRowsList())
                    .isNotNull()
                    .hasSize(rowsReturned)
                    .hasToString(expectedResults.toString());
        } else {
            assertThat(queryResult.getRowsList())
                    .isEmpty();
        }
    }

    private void query(
            int rowsReturned,
            String sql,
            List<Value> args,
            List<Row> expectedResults
    ) {
        ConnectionString connectionString = ConnectionString.newBuilder()
                .setUrl(dataSourceProperties.getUrl())
                .addAllProps(dataSourceProperties.getProps().entrySet().stream()
                        .map(e -> ConnectionStringProp.newBuilder()
                                .setName(e.getKey())
                                .setValue(e.getValue())
                                .build())
                        .toList())
                .build();
        QueryResult queryResult = databaseProxyServiceClient.query(QueryConfig.newBuilder()
                .setConnectionString(connectionString)
                .setTimeout(2_000)
                .setQuery(sql)
                .addAllArgs(args)
                .build());
        assertThat(queryResult)
                .isNotNull();
        if (rowsReturned > 0) {
            assertThat(queryResult.getRowsList())
                    .isNotNull()
                    .hasSize(rowsReturned)
                    .hasToString(expectedResults.toString());
        } else {
            assertThat(queryResult.getRowsList())
                    .isEmpty();
        }
    }
}

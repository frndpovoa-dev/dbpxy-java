package com.dbpxy.springframework;

/*-
 * #%L
 * dbpxy-lib
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

import com.dbpxy.ConnectionHolder;
import jakarta.persistence.EntityManagerFactory;
import lombok.Builder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Builder
public class TransactionExecutionListener implements org.springframework.transaction.TransactionExecutionListener {
    private final EntityManagerFactory entityManagerFactory;
    private final ConnectionHolder connectionHolder;

    @Override
    public void afterBegin(
            @NonNull final TransactionExecution transaction,
            @Nullable final Throwable beginFailure) {
        final EntityManagerHolder entityManagerHolder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        if (entityManagerHolder != null && entityManagerHolder.hasTimeout()) {
            final long timeout = entityManagerHolder.getTimeToLiveInMillis();
            connectionHolder.getConnection().setTransactionTimeoutInMs(timeout);
        }
    }
}

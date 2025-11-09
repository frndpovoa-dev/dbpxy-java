package com.dbpxy.config;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
public class HeadersInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(
            final WebGraphQlRequest request,
            final Chain chain
    ) {
        final HttpHeaders headers = request.getHeaders();
        final String transactionId = Optional.ofNullable(headers.getFirst(Headers.TRANSACTION)).orElse("");
        request.configureExecutionInput((executionInput, builder) ->
                builder.graphQLContext(context -> context
                        .put(Headers.TRANSACTION, transactionId)
                ).build()
        );
        return chain.next(request);
    }
}

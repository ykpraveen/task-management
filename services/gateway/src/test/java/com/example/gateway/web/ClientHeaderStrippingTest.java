package com.example.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ClientHeaderStrippingTest {

    private final ClientHeaderStripping stripping = new ClientHeaderStripping();

    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For", "x-forwarded-host", "X-Forwarded-Proto", "Forwarded", "X-Real-IP",
            "X-Internal-Caller", "X-INTERNAL-SCOPE", "X-User-Id", "X-Auth-Request-User", "X-Remote-User",
            "True-Client-IP", "X-Original-URL"})
    void stripsForwardingAndIdentityHeaders(String name) {
        assertThat(ClientHeaderStripping.stripped(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "Content-Type", "If-Match", "Idempotency-Key", "Range", "Origin",
            "apikey", "Sec-WebSocket-Key", "X-Client-Info"})
    void keepsOrdinaryHeaders(String name) {
        assertThat(ClientHeaderStripping.stripped(name)).isFalse();
    }

    @Test
    void filtersAllValuesOfStrippedHeaders() {
        HttpHeaders input = new HttpHeaders();
        input.add("Authorization", "Bearer t");
        input.add("X-Forwarded-For", "10.0.0.1");
        input.add("X-Forwarded-For", "10.0.0.2");
        input.add("Accept", "application/json");

        HttpHeaders output = stripping.filter(input, MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks")));

        assertThat(output.getFirst("Authorization")).isEqualTo("Bearer t");
        assertThat(output.getFirst("Accept")).isEqualTo("application/json");
        assertThat(output.containsHeader("X-Forwarded-For")).isFalse();
    }
}

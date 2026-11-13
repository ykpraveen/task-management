package com.example.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter(
            new GatewayProperties("http://localhost:8080/", new GatewayProperties.RateLimits(120, 30, 10)));

    @Test
    void allowsRequestsWithoutOriginExceptRealtimeUpgrades() {
        assertThat(run(MockServerHttpRequest.get("/api/v1/tasks"))).isNull();
        assertThat(run(upgrade())).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsTheConfiguredOriginIgnoringCaseAndTrailingSlash() {
        assertThat(run(MockServerHttpRequest.post("/api/v1/tasks").header(HttpHeaders.ORIGIN, "http://LOCALHOST:8080"))).isNull();
        assertThat(run(upgrade().header(HttpHeaders.ORIGIN, "http://localhost:8080"))).isNull();
    }

    @Test
    void rejectsOtherOrigins() {
        assertThat(run(MockServerHttpRequest.post("/api/v1/tasks").header(HttpHeaders.ORIGIN, "http://localhost:8081")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(MockServerHttpRequest.get("/api/v1/tasks").header(HttpHeaders.ORIGIN, "null")))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(upgrade().header(HttpHeaders.ORIGIN, "https://evil.invalid"))).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static MockServerHttpRequest.BaseBuilder<?> upgrade() {
        return MockServerHttpRequest.get("/realtime/v1/websocket").header(HttpHeaders.UPGRADE, "websocket");
    }

    /** Returns the rejection status, or null when the request reached the chain. */
    private HttpStatus run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        return passed.get() ? null : HttpStatus.resolve(exchange.getResponse().getStatusCode().value());
    }
}

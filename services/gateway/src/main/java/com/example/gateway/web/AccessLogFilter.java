package com.example.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * One redacted line per request: method, path without query string (search terms, the
 * Realtime apikey), status and duration. Headers, bodies and WebSocket frames are never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLogFilter implements WebFilter {

    private static final Logger access = LoggerFactory.getLogger("access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            access.info("{} {} {} {}ms", exchange.getRequest().getMethod(), redactedPath(exchange),
                    status == null ? 0 : status.value(), (System.nanoTime() - started) / 1_000_000);
        });
    }

    static String redactedPath(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}

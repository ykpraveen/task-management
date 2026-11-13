package com.example.gateway.web;

import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The SPA is served from one origin and never makes cross-origin calls. Any request whose
 * Origin differs is rejected, and Realtime upgrades must present the allowed Origin (browsers
 * always send it), so another site cannot open a socket with a victim's session. Runs before
 * Spring Security.
 */
@Component
@Order(-200)
public class OriginCheckFilter implements WebFilter {

    private final String allowedOrigin;

    public OriginCheckFilter(GatewayProperties properties) {
        this.allowedOrigin = normalize(properties.allowedOrigin());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        boolean realtimeUpgrade = isWebSocketUpgrade(exchange)
                && RateLimitFilter.REALTIME_PATH.equals(exchange.getRequest().getPath().pathWithinApplication().value());
        if (origin == null ? realtimeUpgrade : !normalize(origin).equals(allowedOrigin)) {
            return Problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "The request origin is not allowed.");
        }
        return chain.filter(exchange);
    }

    static boolean isWebSocketUpgrade(ServerWebExchange exchange) {
        return "websocket".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst(HttpHeaders.UPGRADE));
    }

    private static String normalize(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

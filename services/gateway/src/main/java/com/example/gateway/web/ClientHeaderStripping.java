package com.example.gateway.web;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Removes client-supplied forwarding and identity headers from every proxied request,
 * including WebSocket upgrades. Downstream services derive identity only from the original
 * bearer token, and the gateway adds no identity headers of its own.
 */
@Component
public class ClientHeaderStripping implements HttpHeadersFilter {

    static final Set<String> NAMES = Set.of("forwarded", "x-real-ip", "x-client-ip", "true-client-ip",
            "x-remote-user", "remote-user", "x-original-url", "x-rewrite-url");
    static final List<String> PREFIXES = List.of("x-forwarded-", "x-internal-", "x-user-", "x-auth-");

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders filtered = new HttpHeaders();
        input.forEach((name, values) -> {
            if (!stripped(name)) {
                filtered.addAll(name, values);
            }
        });
        return filtered;
    }

    static boolean stripped(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return NAMES.contains(lower) || PREFIXES.stream().anyMatch(lower::startsWith);
    }
}

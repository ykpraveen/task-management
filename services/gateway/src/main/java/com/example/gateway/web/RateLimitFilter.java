package com.example.gateway.web;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Per-minute limits: API requests per verified identity, user searches per identity, and new
 * Realtime connections per client address. Runs after Spring Security (order -100), so API
 * requests here already carry a validated token. Exceeding a limit returns 429 with
 * Retry-After.
 */
@Component
@Order(0)
public class RateLimitFilter implements WebFilter {

    static final String REALTIME_PATH = "/realtime/v1/websocket";
    static final String USERS_PATH = "/api/v1/users";

    private final FixedWindowRateLimiter api;
    private final FixedWindowRateLimiter userSearch;
    private final FixedWindowRateLimiter realtime;
    // The edge overwrites X-Forwarded-For with its peer address; only that last hop is trusted.
    private final RemoteAddressResolver clientAddress = XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    @Autowired
    public RateLimitFilter(GatewayProperties properties) {
        this(properties.rateLimits(), Clock.systemUTC());
    }

    RateLimitFilter(GatewayProperties.RateLimits limits, Clock clock) {
        Duration minute = Duration.ofMinutes(1);
        this.api = new FixedWindowRateLimiter(limits.apiPerMinute(), minute, clock);
        this.userSearch = new FixedWindowRateLimiter(limits.userSearchPerMinute(), minute, clock);
        this.realtime = new FixedWindowRateLimiter(limits.realtimeConnectionsPerMinute(), minute, clock);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.equals(REALTIME_PATH) && OriginCheckFilter.isWebSocketUpgrade(exchange)) {
            InetSocketAddress address = clientAddress.resolve(exchange);
            String key = address == null ? "unknown" : address.getHostString();
            return admit(realtime.tryAcquire(key), exchange, chain);
        }
        if (!path.startsWith("/api/v1/")) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(identity -> {
                    if (identity.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    FixedWindowRateLimiter.Decision decision = api.tryAcquire(identity);
                    if (decision.allowed() && isUserSearch(exchange, path)) {
                        decision = userSearch.tryAcquire(identity);
                    }
                    return admit(decision, exchange, chain);
                });
    }

    private static boolean isUserSearch(ServerWebExchange exchange, String path) {
        return HttpMethod.GET.equals(exchange.getRequest().getMethod())
                && (path.equals(USERS_PATH) || path.startsWith(USERS_PATH + "/"));
    }

    private static Mono<Void> admit(FixedWindowRateLimiter.Decision decision, ServerWebExchange exchange, WebFilterChain chain) {
        if (decision.allowed()) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set("Retry-After", Long.toString(decision.retryAfterSeconds()));
        return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Try again later.");
    }
}

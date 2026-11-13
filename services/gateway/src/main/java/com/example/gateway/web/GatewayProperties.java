package com.example.gateway.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single frontend origin allowed for browser requests and Realtime upgrades, and the
 * per-minute limits (plan.md, "Validation and security gates").
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String allowedOrigin, RateLimits rateLimits) {

    public record RateLimits(int apiPerMinute, int userSearchPerMinute, int realtimeConnectionsPerMinute) {
    }
}

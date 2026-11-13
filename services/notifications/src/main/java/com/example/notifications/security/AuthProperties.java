package com.example.notifications.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exact issuer and audience; keys come only from the configured JWKS URI (ES256). */
@ConfigurationProperties("auth")
public record AuthProperties(String issuer, String audience, String jwksUri) {
}

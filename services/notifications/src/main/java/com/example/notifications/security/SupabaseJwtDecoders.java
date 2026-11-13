package com.example.notifications.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Supabase user-token validation shared by every resource server: ES256 signatures from the
 * JWKS (keys are cached and refetched when an unknown key ID appears), exact issuer, audience,
 * expiry and not-before, a UUID subject, and the {@code authenticated} role, which rejects
 * Supabase API keys (anon/service_role JWTs) presented as user tokens.
 */
public final class SupabaseJwtDecoders {

    private SupabaseJwtDecoders() {
    }

    public static NimbusJwtDecoder decoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(AuthProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                jwt -> check(jwt.getAudience() != null && jwt.getAudience().contains(properties.audience()), "invalid audience"),
                jwt -> check("authenticated".equals(jwt.getClaimAsString("role")), "not a user token"),
                jwt -> check(isUuid(jwt.getClaimAsString(JwtClaimNames.SUB)), "invalid subject"));
    }

    private static OAuth2TokenValidatorResult check(boolean ok, String description) {
        return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(List.of(new OAuth2Error("invalid_token", description, null)));
    }

    static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

package com.example.notifications.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseJwtDecodersTest {

    private final OAuth2TokenValidator<Jwt> validator = SupabaseJwtDecoders.validator(
            new AuthProperties("http://localhost:8080/auth/v1", "authenticated", "http://unused"));

    @Test
    void acceptsAnActiveUserToken() {
        assertThat(validator.validate(jwt(b -> {
        })).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongIssuerAudienceAndExpiry() {
        assertThat(validator.validate(jwt(b -> b.issuer("http://localhost:8080/auth/v1/"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.issuer("http://evil.invalid/auth/v1"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.audience(List.of("anon")))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.issuedAt(Instant.now().minusSeconds(900))
                .expiresAt(Instant.now().minusSeconds(120)))).hasErrors()).isTrue();
    }

    @Test
    void rejectsApiKeysPresentedAsUserTokens() {
        assertThat(validator.validate(jwt(b -> b.claim("role", "anon"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.claim("role", "service_role"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.claims(c -> c.remove("role")))).hasErrors()).isTrue();
    }

    @Test
    void requiresAUuidSubject() {
        assertThat(validator.validate(jwt(b -> b.subject("admin"))).hasErrors()).isTrue();
        assertThat(SupabaseJwtDecoders.isUuid(null)).isFalse();
        assertThat(SupabaseJwtDecoders.isUuid("6f1c2b8e-4d3a-4f5e-9a7b-1c2d3e4f5a6")).isFalse();
        assertThat(SupabaseJwtDecoders.isUuid("6f1c2b8e-4d3a-4f5e-9a7b-1c2d3e4f5a6b")).isTrue();
    }

    private static Jwt jwt(Consumer<Jwt.Builder> customize) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .issuer("http://localhost:8080/auth/v1")
                .audience(List.of("authenticated"))
                .subject(UUID.randomUUID().toString())
                .claim("role", "authenticated")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600));
        customize.accept(builder);
        return builder.build();
    }
}

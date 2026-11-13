package com.example.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class GatewayJwtValidatorTest {

    private final OAuth2TokenValidator<Jwt> validator = GatewaySecurityConfiguration.validator(
            new AuthProperties("http://localhost:8080/auth/v1", "authenticated", "http://unused"));

    @Test
    void acceptsAnActiveUserToken() {
        assertThat(validator.validate(jwt(b -> {
        })).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongIssuerAudienceRoleSubjectAndExpiry() {
        assertThat(validator.validate(jwt(b -> b.issuer("http://localhost:8080/auth/v1/"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.audience(List.of("anon")))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.claim("role", "service_role"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.claims(c -> c.remove("role")))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.subject("not-a-uuid"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(b -> b.issuedAt(Instant.now().minusSeconds(900))
                .expiresAt(Instant.now().minusSeconds(120)))).hasErrors()).isTrue();
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

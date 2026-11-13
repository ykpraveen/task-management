package com.example.gateway.security;

import com.example.gateway.web.Problems;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/**
 * {@code /api/v1/**} is a resource server with the same rules as the services: ES256 from the
 * JWKS (cached, refetched on an unknown key ID), exact issuer, audience, expiry, UUID subject
 * and the {@code authenticated} role, which rejects Supabase API keys presented as user
 * tokens. The gateway only rejects early and keys rate limits; services revalidate the
 * original bearer token and check the directory. Auth and the Realtime upgrade carry their
 * own credentials and are not authenticated here.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {

    @Bean
    ReactiveJwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        return stateless(http)
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/api/v1/**"))
                .authorizeExchange(a -> a.anyExchange().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtDecoder(decoder))
                        .authenticationEntryPoint((exchange, e) -> Problems.write(exchange, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "A valid user access token is required."))
                        .accessDeniedHandler((exchange, e) -> Problems.write(exchange, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "This operation is not allowed.")))
                .build();
    }

    /** Everything else is decided by the route allowlist; unmatched paths are 404. */
    @Bean
    @Order(2)
    SecurityWebFilterChain otherSecurity(ServerHttpSecurity http) {
        return stateless(http)
                .authorizeExchange(a -> a.anyExchange().permitAll())
                .build();
    }

    private static ServerHttpSecurity stateless(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .anonymous(ServerHttpSecurity.AnonymousSpec::disable)
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
    }

    static OAuth2TokenValidator<Jwt> validator(AuthProperties properties) {
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

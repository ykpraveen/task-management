package com.example.notifications.security;

import com.example.notifications.operations.ProblemCode;
import com.example.notifications.operations.ProblemResponses;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Public API: Supabase user JWTs only (see {@link SupabaseJwtDecoders}) plus the directory active check. */
@Configuration
@Profile("!migrate")
@EnableConfigurationProperties(AuthProperties.class)
public class JwtSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        return SupabaseJwtDecoders.decoder(properties);
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, ActiveUserDirectory directory) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder))
                        .authenticationEntryPoint((req, res, e) ->
                                ProblemResponses.write(res, ProblemCode.UNAUTHENTICATED, "A valid user access token is required.")))
                .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) ->
                        ProblemResponses.write(res, ProblemCode.FORBIDDEN, "This operation is not allowed.")))
                .addFilterBefore(new InternalHeaderRejectionFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new ActiveUserFilter(directory), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(99)
    SecurityFilterChain denyEverythingElse(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, ProblemCode.NOT_FOUND, "Not found."))
                        .accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, ProblemCode.NOT_FOUND, "Not found.")));
        return http.build();
    }
}

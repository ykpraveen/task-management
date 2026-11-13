package com.example.notifications.security;

import com.example.notifications.operations.ProblemCode;
import com.example.notifications.operations.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Internal surface ({@code /internal/v1/operator/**}: email policy and safe replay): never
 * publicly routed, and private networking is not trusted. Only the operator credential is
 * accepted; user JWTs are not. Outbound calls to the task API use {@link TaskApiClientConfiguration}.
 */
@Configuration
@Profile("!migrate")
@EnableConfigurationProperties(InternalServiceSecurity.InternalCallers.class)
public class InternalServiceSecurity {

    /** Header namespace reserved for internal calls; rejected on public requests. */
    public static final String INTERNAL_HEADER_PREFIX = "x-internal-";

    /** Bounded deadline for every internal call (contracts/http-api.md). */
    public static final int INTERNAL_TIMEOUT_SECONDS = 5;

    public static final String SCOPE_OPERATOR = "operator";

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurity(HttpSecurity http, InternalCallers callers) throws Exception {
        http.securityMatcher("/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(callers), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/internal/v1/operator/**").hasAuthority(SCOPE_OPERATOR)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, ProblemCode.UNAUTHENTICATED, "Internal credentials are required."))
                        .accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, ProblemCode.FORBIDDEN, "The caller is not allowed this operation.")));
        return http.build();
    }

    /**
     * Management endpoints listen only on the private management port. Liveness/readiness are
     * open to the container health check and report no details; metrics need operator scope.
     */
    @Bean
    @Order(0)
    SecurityFilterChain managementSecurity(HttpSecurity http, InternalCallers callers) throws Exception {
        http.securityMatcher("/actuator/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(callers), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyRequest().hasAuthority(SCOPE_OPERATOR))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, ProblemCode.UNAUTHENTICATED, "Operator credentials are required."))
                        .accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, ProblemCode.FORBIDDEN, "Operator credentials are required.")));
        return http.build();
    }

    /** Configured callers: name -> token and scopes. Tokens are runtime secrets. */
    @ConfigurationProperties("internal")
    public record InternalCallers(Map<String, Caller> callers) {

        public record Caller(String token, Set<String> scopes) {
        }

        Caller authenticate(String presented) {
            if (presented == null || callers == null) {
                return null;
            }
            byte[] given = presented.getBytes(StandardCharsets.UTF_8);
            Caller match = null;
            for (Caller caller : callers.values()) {
                if (caller.token() != null && caller.token().length() >= 16
                        && MessageDigest.isEqual(given, caller.token().getBytes(StandardCharsets.UTF_8))) {
                    match = caller;
                }
            }
            return match;
        }
    }

    static final class InternalTokenFilter extends OncePerRequestFilter {

        private final InternalCallers callers;

        InternalTokenFilter(InternalCallers callers) {
            this.callers = callers;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                InternalCallers.Caller caller = callers.authenticate(header.substring(7));
                if (caller != null) {
                    List<SimpleGrantedAuthority> authorities = caller.scopes().stream().map(SimpleGrantedAuthority::new).toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("internal-caller", null, authorities));
                }
            }
            chain.doFilter(request, response);
        }
    }
}

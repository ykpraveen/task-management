package com.example.notifications.security;

import com.example.notifications.operations.ProblemCode;
import com.example.notifications.operations.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A valid JWT alone does not prove the user is still active: every request rechecks the
 * directory and fails closed (503) when activity cannot be established.
 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final ActiveUserDirectory directory;

    public ActiveUserFilter(ActiveUserDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            switch (directory.status(UUID.fromString(token.getToken().getSubject()))) {
                case ACTIVE -> {
                }
                case INACTIVE -> {
                    SecurityContextHolder.clearContext();
                    ProblemResponses.write(response, ProblemCode.UNAUTHENTICATED, "The account is not active.");
                    return;
                }
                case UNAVAILABLE -> {
                    ProblemResponses.write(response, ProblemCode.DEPENDENCY_UNAVAILABLE, "Account status cannot be verified right now.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}

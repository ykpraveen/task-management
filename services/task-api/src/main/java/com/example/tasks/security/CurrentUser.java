package com.example.tasks.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The verified, active caller: the Supabase Auth UUID from the validated token subject. */
public record CurrentUser(UUID id) {

    public static CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return new CurrentUser(UUID.fromString(token.getToken().getSubject()));
        }
        throw new IllegalStateException("no authenticated user");
    }
}

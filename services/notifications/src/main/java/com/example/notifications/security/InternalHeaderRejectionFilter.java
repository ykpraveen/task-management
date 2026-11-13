package com.example.notifications.security;

import com.example.notifications.operations.ProblemCode;
import com.example.notifications.operations.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.web.filter.OncePerRequestFilter;

/** Public requests never carry internal-caller headers; their presence is an attack, not a hint. */
public class InternalHeaderRejectionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase().startsWith(InternalServiceSecurity.INTERNAL_HEADER_PREFIX)) {
                ProblemResponses.write(response, ProblemCode.FORBIDDEN, "Internal headers are not accepted on public requests.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

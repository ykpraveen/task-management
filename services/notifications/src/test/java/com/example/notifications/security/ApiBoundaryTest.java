package com.example.notifications.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.notifications.operations.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The servlet security chains and error mapping with a stub token decoder and directory:
 * public JWT access with the per-request active check, the operator-only internal surface,
 * internal-header rejection, and problem responses that leak nothing.
 */
@WebMvcTest(controllers = ApiBoundaryTest.ProbeController.class)
@Import({JwtSecurityConfiguration.class, InternalServiceSecurity.class, ApiBoundaryTest.ProbeController.class})
@TestPropertySource(properties = {
        "internal.callers.operator.token=operator-token-0123456789",
        "internal.callers.operator.scopes=operator",
        "internal.callers.misconfigured.token=short",
        "internal.callers.misconfigured.scopes=operator"})
class ApiBoundaryTest {

    static final UUID USER = UUID.fromString("6f1c2b8e-4d3a-4f5e-9a7b-1c2d3e4f5a6b");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    ActiveUserDirectory directory;

    @BeforeEach
    void tokens() {
        doThrow(new BadJwtException("invalid")).when(jwtDecoder).decode(any());
        doReturn(Jwt.withTokenValue("user-token")
                .header("alg", "ES256").subject(USER.toString()).claim("role", "authenticated")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()).when(jwtDecoder).decode("user-token");
        when(directory.status(USER)).thenReturn(ActiveUserDirectory.Status.ACTIVE);
    }

    // ------------------------------------------------------------------ public API

    @Test
    void rejectsMissingAndInvalidTokens() throws Exception {
        mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
    }

    @Test
    void mapsTheSubjectOfAnActiveUser() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk()).andExpect(content().string(USER.toString()));
    }

    @Test
    void rejectsInactiveUsersOnEveryRequest() throws Exception {
        when(directory.status(USER)).thenReturn(ActiveUserDirectory.Status.INACTIVE);
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void failsClosedWhenTheDirectoryIsUnavailable() throws Exception {
        when(directory.status(USER)).thenReturn(ActiveUserDirectory.Status.UNAVAILABLE);
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer user-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void rejectsPublicRequestsCarryingInternalHeaders() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", "Bearer user-token").header("X-Internal-Caller", "notifications"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ internal API

    @Test
    void requiresInternalCredentials() throws Exception {
        mvc.perform(post("/internal/v1/operator/replay")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/operator/replay").header("Authorization", "Bearer wrong-token-0123456789"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotAcceptUserTokensInternally() throws Exception {
        mvc.perform(post("/internal/v1/operator/replay").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsOnlyTheOperatorSurface() throws Exception {
        mvc.perform(post("/internal/v1/operator/replay").header("Authorization", "Bearer operator-token-0123456789"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/outbox/claim").header("Authorization", "Bearer operator-token-0123456789"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ignoresCredentialsShorterThanSixteenCharacters() throws Exception {
        mvc.perform(post("/internal/v1/operator/replay").header("Authorization", "Bearer short"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ everything else

    @Test
    void deniesUnlistedPathsAsNotFound() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isNotFound());
    }

    @Test
    void mapsFailuresToProblemsWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/api/v1/probe/validation").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().exists("X-Request-Id"));
        mvc.perform(get("/api/v1/probe/failure").header("Authorization", "Bearer user-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("private task text"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe")
        String probe() {
            return CurrentUser.require().id().toString();
        }

        @GetMapping("/api/v1/probe/validation")
        String validation() {
            throw ApiException.validation("title", "REQUIRED", "Title is required.");
        }

        @GetMapping("/api/v1/probe/failure")
        String failure() {
            throw new IllegalStateException("private task text");
        }

        @PostMapping("/internal/v1/outbox/claim")
        String claim() {
            return "claimed";
        }

        @PostMapping("/internal/v1/operator/replay")
        String replay() {
            return "replayed";
        }
    }
}

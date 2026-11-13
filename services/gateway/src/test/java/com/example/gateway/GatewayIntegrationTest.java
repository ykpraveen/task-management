package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/**
 * Runs the real gateway against stub upstreams (task API, notifications, Supabase gateway
 * with a JWKS and a WebSocket endpoint) on loopback ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@ExtendWith(OutputCaptureExtension.class)
class GatewayIntegrationTest {

    static final String ORIGIN = "http://localhost:8080";
    static final TestTokens TOKENS = new TestTokens();
    static final DisposableServer TASK_API = stub("task-api");
    static final DisposableServer NOTIFICATIONS = stub("notifications");
    static final DisposableServer SUPABASE = stub("supabase");

    @LocalServerPort
    int port;

    WebTestClient client;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("TASK_API_URL", () -> "http://127.0.0.1:" + TASK_API.port());
        registry.add("NOTIFICATIONS_URL", () -> "http://127.0.0.1:" + NOTIFICATIONS.port());
        registry.add("SUPABASE_GATEWAY_URL", () -> "http://127.0.0.1:" + SUPABASE.port());
        registry.add("auth.jwks-uri", () -> "http://127.0.0.1:" + SUPABASE.port() + "/jwks");
        registry.add("auth.issuer", () -> TestTokens.ISSUER);
        registry.add("gateway.allowed-origin", () -> ORIGIN);
    }

    @AfterAll
    static void stopStubs() {
        TASK_API.disposeNow();
        NOTIFICATIONS.disposeNow();
        SUPABASE.disposeNow();
    }

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    // ------------------------------------------------------------------ route allowlist

    @Test
    void routesTaskAndUserPathsToTheTaskApi() {
        String token = TOKENS.valid();
        get("/api/v1/tasks", token).expectStatus().isOk().expectBody().jsonPath("$.upstream").isEqualTo("task-api");
        get("/api/v1/tasks/9007199254740993/comments", token).expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo("task-api")
                .jsonPath("$.path").isEqualTo("/api/v1/tasks/9007199254740993/comments");
        get("/api/v1/users?query=ann", token).expectStatus().isOk().expectBody().jsonPath("$.upstream").isEqualTo("task-api");
    }

    @Test
    void routesNotificationPathsToTheNotificationService() {
        get("/api/v1/notifications/unread-count", TOKENS.valid()).expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo("notifications");
    }

    @Test
    void routesAuthAndTheSigningKeyPathToSupabaseWithoutAUserToken() {
        client.post().uri("/auth/v1/token?grant_type=password").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo("supabase");
        client.get().uri("/auth/v1/.well-known/jwks.json").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo("supabase");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/outbox/claim", "/internal/v1/authorization/check", "/rest/v1/tasks",
            "/storage/v1/object/task-attachments/x", "/realtime/v1/api/broadcast", "/pg/tables", "/", "/anything"})
    void deniesPathsOutsideTheAllowlist(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
        client.post().uri(path).exchange().expectStatus().isNotFound();
    }

    @Test
    void deniesUnroutedApiPathsEvenWithAValidToken() {
        get("/api/v1/internal/outbox", TOKENS.valid()).expectStatus().isNotFound();
        get("/api/v1/admin", TOKENS.valid()).expectStatus().isNotFound();
    }

    @Test
    void doesNotRouteRealtimeWithoutAWebSocketUpgrade() {
        client.get().uri("/realtime/v1/websocket").header(HttpHeaders.ORIGIN, ORIGIN).exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ JWT rejection

    @Test
    void rejectsMissingTokensWithAProblem() {
        client.get().uri("/api/v1/tasks").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void rejectsInvalidTokens() {
        Instant past = Instant.now().minusSeconds(900);
        Map<String, String> tokens = Map.of(
                "expired", TOKENS.withClaims(c -> c.issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(300)))),
                "wrong issuer", TOKENS.withClaims(c -> c.issuer("http://evil.invalid/auth/v1")),
                "wrong audience", TOKENS.withClaims(c -> c.audience("other")),
                "API key role", TOKENS.withClaims(c -> c.claim("role", "anon")),
                "service role", TOKENS.withClaims(c -> c.claim("role", "service_role")),
                "non-UUID subject", TOKENS.withClaims(c -> c.subject("admin")),
                "RS256", TOKENS.rs256(),
                "HS256", TOKENS.hs256(),
                "unknown key", TOKENS.signedWithUnknownKey(),
                "garbage", "not.a.jwt");
        tokens.forEach((name, token) -> get("/api/v1/tasks", token)
                .expectStatus().value(status -> assertThat(status).as(name).isEqualTo(401)));
    }

    @Test
    void forwardsTheOriginalBearerTokenUnchanged() {
        String token = TOKENS.valid();
        get("/api/v1/tasks", token).expectStatus().isOk()
                .expectBody().jsonPath("$.authorization").isEqualTo("Bearer " + token);
    }

    // ------------------------------------------------------------------ rate limits

    @Test
    void limitsApiRequestsPerIdentityWithRetryAfter() {
        awaitFreshWindow();
        String token = TOKENS.valid();
        for (int i = 0; i < 120; i++) {
            get("/api/v1/tasks", token).expectStatus().isOk();
        }
        get("/api/v1/tasks", token)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Integer.parseInt(value)).isBetween(1, 60))
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
        // Another identity is unaffected.
        get("/api/v1/tasks", TOKENS.valid()).expectStatus().isOk();
    }

    @Test
    void limitsUserSearchesSeparately() {
        awaitFreshWindow();
        String token = TOKENS.valid();
        for (int i = 0; i < 30; i++) {
            get("/api/v1/users?query=a", token).expectStatus().isOk();
        }
        get("/api/v1/users?query=a", token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        get("/api/v1/tasks", token).expectStatus().isOk();
    }

    @Test
    void limitsNewRealtimeConnectionsPerClientAddress() {
        awaitFreshWindow();
        String address = "203.0.113.10";
        for (int i = 0; i < 10; i++) {
            assertThat(openRealtime(address, ORIGIN)).startsWith("hello");
        }
        upgradeRequest(address, ORIGIN).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
        assertThat(openRealtime("203.0.113.11", ORIGIN)).startsWith("hello");
    }

    // ------------------------------------------------------------------ origin checks

    @Test
    void rejectsCrossOriginApiRequests() {
        client.get().uri("/api/v1/tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.valid())
                .header(HttpHeaders.ORIGIN, "https://evil.invalid").exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.get().uri("/api/v1/tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.valid())
                .header(HttpHeaders.ORIGIN, ORIGIN).exchange().expectStatus().isOk();
        client.post().uri("/auth/v1/token").header(HttpHeaders.ORIGIN, "https://evil.invalid").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsRealtimeUpgradesWithoutTheAllowedOrigin() {
        upgradeRequest("203.0.113.20", null).expectStatus().isForbidden();
        upgradeRequest("203.0.113.20", "https://evil.invalid").expectStatus().isForbidden();
        assertThatThrownBy(() -> openRealtime("203.0.113.20", "https://evil.invalid")).isInstanceOf(Exception.class);
    }

    // ------------------------------------------------------------------ WebSocket upgrade

    @Test
    void proxiesRealtimeUpgradesWithoutRequiringAnAuthorizationHeader() {
        assertThat(openRealtime("203.0.113.30", ORIGIN)).isEqualTo("hello apikey=present");
    }

    // ------------------------------------------------------------------ header stripping

    @Test
    void stripsClientForwardingAndIdentityHeaders() {
        client.get().uri("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.valid())
                .header("X-Forwarded-For", "10.0.0.1")
                .header("X-Forwarded-Host", "evil.invalid")
                .header("Forwarded", "for=10.0.0.1")
                .header("X-Real-IP", "10.0.0.1")
                .header("X-Internal-Caller", "notifications")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-Auth-Subject", "admin")
                .header("X-Custom", "kept")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.headers").value(String.class, names -> {
                    List<String> headers = List.of(names.split(","));
                    assertThat(headers).contains("authorization", "x-custom")
                            .doesNotContain("x-forwarded-for", "x-forwarded-host", "forwarded", "x-real-ip",
                                    "x-internal-caller", "x-user-id", "x-auth-subject");
                });
    }

    // ------------------------------------------------------------------ log redaction

    @Test
    void accessLogsOmitQueryStringsAndFrames(CapturedOutput output) {
        get("/api/v1/tasks?view=assigned&query=secret-search-term", TOKENS.valid()).expectStatus().isOk();
        assertThat(openRealtime("203.0.113.40", ORIGIN)).startsWith("hello");

        assertThat(output.getAll()).contains("/api/v1/tasks").contains("/realtime/v1/websocket")
                .doesNotContain("secret-search-term").doesNotContain("publishable-key-value")
                .doesNotContain("hello apikey");
    }

    // ------------------------------------------------------------------ helpers

    /** Limits use wall-clock minute windows; avoid a rollover in the middle of a limit test. */
    private static void awaitFreshWindow() {
        long intoMinute = System.currentTimeMillis() % 60_000;
        if (intoMinute > 50_000) {
            try {
                Thread.sleep(60_000 - intoMinute + 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private WebTestClient.ResponseSpec upgradeRequest(String address, String origin) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri("/realtime/v1/websocket?apikey=x")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header("X-Forwarded-For", address);
        if (origin != null) {
            spec = spec.header(HttpHeaders.ORIGIN, origin);
        }
        return spec.exchange();
    }

    private String openRealtime(String address, String origin) {
        return HttpClient.create()
                .headers(h -> h.set(HttpHeaders.ORIGIN, origin).set("X-Forwarded-For", address))
                .websocket()
                .uri("ws://127.0.0.1:" + port + "/realtime/v1/websocket?apikey=publishable-key-value&vsn=1.0.0")
                .handle((in, out) -> in.receive().asString().take(1))
                .blockFirst(Duration.ofSeconds(10));
    }

    /** Echoes the upstream name, path, received header names and Authorization as JSON. */
    private static DisposableServer stub(String name) {
        return HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes
                        .get("/jwks", (req, res) -> res.header("Content-Type", "application/json").sendString(Mono.just(TOKENS.jwks())))
                        .route(req -> req.uri().startsWith("/realtime/v1/websocket"), (req, res) -> res.sendWebsocket(
                                (in, out) -> out.sendString(Mono.just("hello apikey="
                                        + (req.uri().contains("apikey=publishable-key-value") ? "present" : "missing")))))
                        .route(req -> true, (req, res) -> {
                            String headers = req.requestHeaders().names().stream()
                                    .map(String::toLowerCase).sorted().collect(Collectors.joining(","));
                            String authorization = req.requestHeaders().get(HttpHeaders.AUTHORIZATION, "");
                            String body = "{\"upstream\":\"" + name + "\",\"path\":\"" + req.fullPath()
                                    + "\",\"headers\":\"" + headers + "\",\"authorization\":\"" + authorization + "\"}";
                            return res.header("Content-Type", "application/json").sendString(Mono.just(body));
                        }))
                .bindNow();
    }
}

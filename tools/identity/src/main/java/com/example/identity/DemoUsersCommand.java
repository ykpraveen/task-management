package com.example.identity;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Creates the synthetic demo accounts through server-side Auth administration.
 * Idempotent: existing accounts are updated (password, confirmation, profile), not duplicated.
 * The operator supplies the password; it is never printed.
 */
@Component
public class DemoUsersCommand {

    static final int EXIT_OK = 0;
    static final int EXIT_CONFIG = 78;
    static final int EXIT_FAILED = 1;
    static final int MIN_PASSWORD_LENGTH = 12;

    /** Synthetic accounts with distinct time zones (quickstart.md). */
    static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("creator", "creator@demo.invalid", "Casey Creator", "Europe/Berlin"),
            new DemoUser("assignee", "assignee@demo.invalid", "Avery Assignee", "America/New_York"),
            new DemoUser("replacement", "replacement@demo.invalid", "Riley Replacement", "Asia/Tokyo"),
            new DemoUser("outsider", "outsider@demo.invalid", "Oakley Outsider", "Australia/Sydney"));

    private final RestClient auth;
    private final IdentityToolProperties properties;
    private final PrintStream out;

    @Autowired
    public DemoUsersCommand(RestClient.Builder builder, IdentityToolProperties properties) {
        this(builder, properties, System.out);
    }

    DemoUsersCommand(RestClient.Builder builder, IdentityToolProperties properties, PrintStream out) {
        this.properties = properties;
        this.out = out;
        this.auth = builder
                .baseUrl(properties.authAdminUrl() == null ? "" : properties.authAdminUrl())
                .defaultHeader("apikey", properties.secretKey() == null ? "" : properties.secretKey())
                .build();
    }

    public int run() {
        String password = properties.demoPassword();
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            out.println("demo-users: SUPABASE_SECRET_KEY is not configured");
            return EXIT_CONFIG;
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            out.println("demo-users: set DEMO_PASSWORD (at least " + MIN_PASSWORD_LENGTH + " characters)");
            return EXIT_CONFIG;
        }
        int failures = 0;
        for (DemoUser user : DEMO_USERS) {
            try {
                String outcome = upsert(user, password);
                out.println("demo-users: " + user.role() + " " + user.email() + " " + outcome);
            } catch (RuntimeException e) {
                failures++;
                // Message only: response bodies are not echoed.
                out.println("demo-users: " + user.role() + " failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        return failures == 0 ? EXIT_OK : EXIT_FAILED;
    }

    String upsert(DemoUser user, String password) {
        Map<String, Object> body = Map.of(
                "email", user.email(),
                "password", password,
                "email_confirm", true,
                "user_metadata", Map.of("display_name", user.displayName(), "time_zone", user.timeZone()));
        Optional<String> existing = findUserId(user.email());
        if (existing.isPresent()) {
            auth.put().uri("/admin/users/{id}", existing.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return "updated";
        }
        auth.post().uri("/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        return "created";
    }

    private Optional<String> findUserId(String email) {
        for (int page = 1; page <= 100; page++) {
            JsonNode response = auth.get().uri("/admin/users?page={page}&per_page=100", page)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new IllegalStateException("Auth admin list failed with " + res.getStatusCode().value());
                    })
                    .body(JsonNode.class);
            JsonNode users = response == null ? null : response.get("users");
            if (users == null || users.isEmpty()) {
                return Optional.empty();
            }
            for (JsonNode node : users) {
                if (email.equalsIgnoreCase(node.path("email").asString(""))) {
                    return Optional.of(node.path("id").asString());
                }
            }
        }
        return Optional.empty();
    }

    record DemoUser(String role, String email, String displayName, String timeZone) {
    }
}

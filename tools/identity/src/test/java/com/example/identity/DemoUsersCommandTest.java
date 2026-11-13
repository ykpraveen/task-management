package com.example.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DemoUsersCommandTest {

    static final String BASE = "http://auth.test/auth/v1";
    static final String PASSWORD = "operator-Chosen-pw-1";

    RestClient.Builder builder;
    MockRestServiceServer server;
    ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        output = new ByteArrayOutputStream();
    }

    DemoUsersCommand command(String password, String secretKey) {
        return new DemoUsersCommand(builder, new IdentityToolProperties(BASE, secretKey, password),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @Test
    void createsAllDemoAccountsWithDistinctTimeZones() {
        DemoUsersCommand command = command(PASSWORD, "sb_secret_test");
        for (DemoUsersCommand.DemoUser user : DemoUsersCommand.DEMO_USERS) {
            server.expect(requestTo(BASE + "/admin/users?page=1&per_page=100"))
                    .andExpect(header("apikey", "sb_secret_test"))
                    .andRespond(withSuccess("{\"users\":[]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(BASE + "/admin/users")).andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.email").value(user.email()))
                    .andExpect(jsonPath("$.email_confirm").value(true))
                    .andExpect(jsonPath("$.user_metadata.time_zone").value(user.timeZone()))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }

        assertThat(command.run()).isEqualTo(DemoUsersCommand.EXIT_OK);
        server.verify();
        assertThat(DemoUsersCommand.DEMO_USERS).extracting(DemoUsersCommand.DemoUser::timeZone).doesNotHaveDuplicates();
        assertThat(DemoUsersCommand.DEMO_USERS).extracting(DemoUsersCommand.DemoUser::role)
                .containsExactly("creator", "assignee", "replacement", "outsider");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("creator creator@demo.invalid created").doesNotContain(PASSWORD);
    }

    @Test
    void secondRunUpdatesExistingAccountsInsteadOfDuplicating() {
        DemoUsersCommand command = command(PASSWORD, "sb_secret_test");
        int index = 0;
        for (DemoUsersCommand.DemoUser user : DemoUsersCommand.DEMO_USERS) {
            String id = "00000000-0000-0000-0000-00000000000" + (++index);
            server.expect(requestTo(BASE + "/admin/users?page=1&per_page=100"))
                    .andRespond(withSuccess("{\"users\":[{\"id\":\"" + id + "\",\"email\":\"" + user.email().toUpperCase() + "\"}]}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(BASE + "/admin/users/" + id)).andExpect(method(HttpMethod.PUT))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }

        assertThat(command.run()).isEqualTo(DemoUsersCommand.EXIT_OK);
        server.verify();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("updated").doesNotContain("created");
    }

    @Test
    void failureIsReportedWithoutEchoingResponseOrPassword() {
        DemoUsersCommand command = command(PASSWORD, "sb_secret_test");
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/admin/users?page=1&per_page=100"))
                .andRespond(withServerError().body("internal detail " + PASSWORD));

        assertThat(command.run()).isEqualTo(DemoUsersCommand.EXIT_FAILED);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("failed").doesNotContain(PASSWORD).doesNotContain("internal detail");
    }

    @Test
    void refusesMissingOrShortPassword() {
        assertThat(command(null, "sb_secret_test").run()).isEqualTo(DemoUsersCommand.EXIT_CONFIG);
        assertThat(command("short", "sb_secret_test").run()).isEqualTo(DemoUsersCommand.EXIT_CONFIG);
        assertThat(command(PASSWORD, "").run()).isEqualTo(DemoUsersCommand.EXIT_CONFIG);
    }
}

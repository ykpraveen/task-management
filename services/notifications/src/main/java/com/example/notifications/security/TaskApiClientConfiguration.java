package com.example.notifications.security;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client for the task-owned internal API: distinct service credential and five-second
 * connect and read deadlines. Callers retry only idempotent operations.
 */
@Configuration
@Profile("!migrate")
@EnableConfigurationProperties(TaskApiClientConfiguration.TaskApiProperties.class)
public class TaskApiClientConfiguration {

    @ConfigurationProperties("task-api")
    public record TaskApiProperties(String internalUrl, String token) {
    }

    @Bean
    RestClient taskApiClient(RestClient.Builder builder, TaskApiProperties properties) {
        Duration timeout = Duration.ofSeconds(InternalServiceSecurity.INTERNAL_TIMEOUT_SECONDS);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return builder.baseUrl(properties.internalUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.token())
                .build();
    }
}

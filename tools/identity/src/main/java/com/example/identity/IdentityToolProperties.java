package com.example.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("identity")
public record IdentityToolProperties(String authAdminUrl, String secretKey, String demoPassword) {
}

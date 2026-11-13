package com.example.tasks.operations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In migrate mode Flyway has already run during startup; stop instead of serving. */
@Component
@Profile("migrate")
class MigrateModeExit implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    MigrateModeExit(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("migrate: completed");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}

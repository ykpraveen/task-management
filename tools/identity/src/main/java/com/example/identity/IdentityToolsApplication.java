package com.example.identity;

import java.util.Arrays;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Operator CLI for the identity-owned directory projection. Not a long-running service:
 * Supabase Auth remains the identity service.
 *
 * <pre>
 *   reconcile --check    exit 0 when the projection matches auth.users, 2 on drift
 *   reconcile --repair   idempotently repairs drift
 *   demo-users           creates or updates the synthetic demo accounts
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IdentityToolsApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(IdentityToolsApplication.class, args);
        int code = run(context, args);
        System.exit(SpringApplication.exit(context, (ExitCodeGenerator) () -> code));
    }

    static int run(ConfigurableApplicationContext context, String[] args) {
        if (args.length == 0) {
            System.err.println("usage: reconcile --check|--repair | demo-users");
            return 64;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "reconcile" -> context.getBean(ReconcileCommand.class).run(rest);
            case "demo-users" -> context.getBean(DemoUsersCommand.class).run();
            default -> {
                System.err.println("unknown command: " + args[0]);
                yield 64;
            }
        };
    }
}

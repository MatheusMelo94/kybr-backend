package com.matheusmelo.kybr.config.management;

import java.util.Set;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The startup check that keeps the admin endpoint off the public port: when {@code admin} is
 * exposed, {@code management.server.port} must be set and differ from {@code server.port}
 * (Boot's own reading, so two random ports in tests count as different).
 */
public final class ManagementPortGuard {

    static final String ADMIN = "admin";

    private ManagementPortGuard() {
    }

    /** Throws when {@code admin} is exposed and the management server isn't on its own port. */
    public static void check(Environment environment) {
        Set<String> exposed = Binder.get(environment)
                .bind("management.endpoints.web.exposure.include", Bindable.setOf(String.class))
                .orElse(Set.of());
        boolean adminExposed = exposed.contains(ADMIN) || exposed.contains("*");
        if (adminExposed && ManagementPortType.get(environment) != ManagementPortType.DIFFERENT) {
            throw new IllegalStateException("the admin endpoint is exposed, so management.server.port must be set"
                    + " and differ from server.port");
        }
    }
}

package com.matheusmelo.kybr.config.logging;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Configures {@link LogRedaction} from the prepared environment: after the config files are
 * loaded (order +10) and before the logging system initialises (order +20), so the first log
 * line of the application is already redacted with the real settings. Registered in
 * {@code META-INF/spring.factories}.
 */
public class LogRedactionEnvironmentListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        LogRedaction.configure(event.getEnvironment());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}

package com.matheusmelo.kybr.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback {@code %redact(...)}: applies {@link LogRedaction} to the output of the wrapped pattern
 * (message and stack trace included) in the plain, non-JSON profiles. Declared in
 * {@code logback-spring.xml}.
 */
public class RedactingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return LogRedaction.redact(in);
    }
}

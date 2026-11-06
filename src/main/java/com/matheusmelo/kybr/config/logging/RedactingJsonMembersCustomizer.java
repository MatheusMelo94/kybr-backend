package com.matheusmelo.kybr.config.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * Applies {@link LogRedaction} to every string value of a structured (ECS) log event:
 * {@code message}, {@code error.message}, {@code error.stack_trace}, MDC values and the rest
 * so no secret reaches the logs. Registered in {@code META-INF/spring.factories}; Boot
 * instantiates it with the environment when it builds the structured formatter.
 */
public class RedactingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    public RedactingJsonMembersCustomizer(Environment environment) {
        LogRedaction.configure(environment);
    }

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedaction::redact));
    }
}

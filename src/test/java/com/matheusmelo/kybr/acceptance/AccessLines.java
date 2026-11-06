package com.matheusmelo.kybr.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.matheusmelo.kybr.config.filter.AccessLogFilter;
import com.matheusmelo.kybr.support.web.CapturedEvents;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The access log as the client-IP spoof script reads it: captures
 * {@link AccessLogFilter}'s lines and finds the one for a response's {@code X-Request-Id}.
 */
public final class AccessLines implements AutoCloseable {

    private static final Pattern FIELD = Pattern.compile("(\\w+)=(\\[[^\\]]*\\]|\\S+)");

    private final CapturedEvents captured = CapturedEvents.of(AccessLogFilter.class);

    public static AccessLines capture() {
        return new AccessLines();
    }

    /** The fields of the single access line for {@code requestId}; waits briefly (the line is written after the response). */
    public Map<String, String> lineFor(String requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<Map<String, String>> lines;
        do {
            lines = captured.events().stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .map(AccessLines::fields)
                    .filter(f -> requestId.equals(f.get("request_id")))
                    .toList();
            if (!lines.isEmpty()) {
                break;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        assertThat(lines).as("exactly one access line for request " + requestId).hasSize(1);
        return lines.getFirst();
    }

    public List<String> messages() {
        return captured.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    static Map<String, String> fields(String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = FIELD.matcher(message);
        while (m.find()) {
            fields.put(m.group(1), m.group(2));
        }
        return fields;
    }

    @Override
    public void close() {
        captured.close();
    }
}

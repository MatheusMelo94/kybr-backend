package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.util.LogRedactor;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * The access log's safety net for requests Tomcat answers without running any filter (an encoded
 * {@code /}, bad percent-encoding, an oversized header): a host-level valve, first in the host
 * pipeline, that writes the access line only when {@link AccessLogFilter} didn't (such
 * raw-URI variants are still logged, with the path redacted). Its next valves, including the
 * envelope error valve, have run by the time it logs, so the status and {@code X-Request-Id} are
 * final.
 */
final class AccessLogValve extends ValveBase {

    private final LogRedactor redactor;

    AccessLogValve(LogRedactor redactor) {
        super(true);
        this.redactor = redactor;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        long start = System.nanoTime();
        try {
            getNext().invoke(request, response);
        } finally {
            if (request.getAttribute(AccessLogFilter.LOGGED_ATTRIBUTE) == null) {
                String requestId = response.getHeader(RequestIdFilter.HEADER);
                if (requestId != null && request.getAttribute(RequestIdFilter.ATTRIBUTE) == null) {
                    request.setAttribute(RequestIdFilter.ATTRIBUTE, requestId);
                }
                AccessLogFilter.write(request, response.getStatus(), new AccessLogFilter.Pending(start,
                        request.getMethod(), AccessLogFilter.redactedPath(redactor, request.getRequestURI()),
                        AccessLogFilter.queryParams(redactor, request.getQueryString())));
            }
        }
    }
}

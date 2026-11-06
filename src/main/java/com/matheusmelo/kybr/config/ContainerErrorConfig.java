package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.config.filter.ResponseGuardFilter;
import com.matheusmelo.kybr.controller.ApiErrorController;
import com.matheusmelo.kybr.exception.ErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.catalina.Host;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;

/**
 * Covers the errors Tomcat answers without ever dispatching to {@code /error} (an oversized header,
 * a malformed or rejected request target): Tomcat's host-level {@link ErrorReportValve}, which
 * would write an HTML page, is replaced by one that writes the error envelope with a fresh
 * {@code X-Request-Id} and the security headers.
 *
 * <p>Requests Tomcat rejects before the host pipeline (a broken request line, a dropped
 * connection) remain an accepted, documented limitation.
 */
@Configuration(proxyBeanMethods = false)
public class ContainerErrorConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> envelopeErrorReportValve(Environment environment) {
        boolean hsts = ResponseGuardFilter.hstsEnabled(environment);
        return new EnvelopeValveInstaller(hsts);
    }

    /** Runs after Boot's own customizer, which installs a plain {@link ErrorReportValve}. */
    private record EnvelopeValveInstaller(boolean hsts)
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addContextCustomizers(context -> {
                Host host = (Host) context.getParent();
                for (Valve valve : host.getPipeline().getValves()) {
                    if (valve instanceof ErrorReportValve) {
                        host.getPipeline().removeValve(valve);
                    }
                }
                host.getPipeline().addValve(new EnvelopeErrorReportValve(hsts));
                // Stops the host from adding Tomcat's default valve when it starts.
                if (host instanceof org.apache.catalina.core.StandardHost standardHost) {
                    standardHost.setErrorReportValveClass(EnvelopeErrorReportValve.class.getName());
                }
            });
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** Writes the envelope for any error response nothing else has written. */
    static final class EnvelopeErrorReportValve extends ErrorReportValve {

        private final boolean hsts;

        EnvelopeErrorReportValve(boolean hsts) {
            this.hsts = hsts;
            setShowReport(false);
            setShowServerInfo(false);
        }

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int status = response.getStatus();
            if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
                return;
            }
            ErrorCode code = ApiErrorController.codeForStatus(status);
            try {
                response.resetBuffer();
                response.setStatus(code.status().value());
                if (response.getHeader(RequestIdFilter.HEADER) == null) {
                    response.setHeader(RequestIdFilter.HEADER, UUID.randomUUID().toString());
                }
                ResponseGuardFilter.securityHeaders(hsts).forEach(response::setHeader);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                OutputStream out = response.getOutputStream();
                out.write(envelope(code).getBytes(StandardCharsets.UTF_8));
                response.finishResponse();
            } catch (IOException | IllegalStateException e) {
                // The connection is gone or already committed; nothing more can be sent.
            }
        }

        /** The envelope as {@code ErrorResponse} serialises it; codes and messages need no escaping. */
        static String envelope(ErrorCode code) {
            return "{\"code\":\"" + code.name() + "\",\"message\":\"" + code.message() + "\"}";
        }
    }
}

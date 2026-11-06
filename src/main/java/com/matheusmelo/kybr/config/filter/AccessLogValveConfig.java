package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.util.LogRedactor;
import org.apache.catalina.Host;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Installs {@link AccessLogValve} on the host pipeline <b>before</b> the envelope error valve
 * ({@code ContainerErrorConfig}, ordered {@link Ordered#LOWEST_PRECEDENCE}), so it wraps it and
 * logs the final status. Valves can't be reordered once added (removal destroys them), so the
 * order comes from the customizer order.
 */
@Configuration(proxyBeanMethods = false)
public class AccessLogValveConfig {

    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> accessLogValve(LogRedactor redactor) {
        return new Installer(redactor);
    }

    private record Installer(LogRedactor redactor)
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addContextCustomizers(
                    context -> ((Host) context.getParent()).getPipeline().addValve(new AccessLogValve(redactor)));
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }
}

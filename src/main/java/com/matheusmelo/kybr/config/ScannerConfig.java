package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.service.image.SanitizerClient;
import com.matheusmelo.kybr.service.scan.ClamdScanner;
import com.matheusmelo.kybr.service.scan.ScanLinkTls;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The clients of the isolated scanner organisation, over one scan link (mutual TLS, the link's CA,
 * the pinned server key):
 *
 * <ul>
 *   <li>the malware scanner ({@code kybr.scanner.mode=clamd}); otherwise the stand-in scanner;
 *   <li>the photo sanitizer ({@code kybr.image.mode=remote}); otherwise the in-process stand-in.
 * </ul>
 *
 * Prod requires both live clients (the profile guard).
 */
@Configuration(proxyBeanMethods = false)
public class ScannerConfig {

    @Bean
    @Conditional(ScanLinkUsed.class)
    ScanLinkTls scanLinkTls(KybrProperties properties) {
        return scanLinkTls(properties.scanner());
    }

    @Bean
    @ConditionalOnProperty(prefix = "kybr.scanner", name = "mode", havingValue = "clamd")
    ClamdScanner clamdScanner(ScanLinkTls tls, KybrProperties properties, MeterRegistry meters) {
        return clamdScanner(tls, properties.scanner(), meters);
    }

    @Bean
    @ConditionalOnProperty(prefix = "kybr.image", name = "mode", havingValue = "remote")
    SanitizerClient sanitizerClient(ScanLinkTls tls, KybrProperties properties) {
        KybrProperties.Sanitizer sanitizer = properties.sanitizer();
        return new SanitizerClient(tls, sanitizer.host(), sanitizer.port(), properties.scanner().connectTimeout(),
                sanitizer.timeout(), Math.toIntExact(sanitizer.maxResponse().toBytes()));
    }

    static ScanLinkTls scanLinkTls(KybrProperties.Scanner scanner) {
        return ScanLinkTls.fromPem(scanner.clientCert(), scanner.clientKey(), scanner.ca(), scanner.spkiSha256());
    }

    static ClamdScanner clamdScanner(ScanLinkTls tls, KybrProperties.Scanner scanner, MeterRegistry meters) {
        return new ClamdScanner(tls, scanner.host(), scanner.port(), scanner.connectTimeout(), scanner.readTimeout(),
                meters);
    }

    /** The live scanner or the remote sanitizer is configured. */
    static final class ScanLinkUsed extends AnyNestedCondition {

        ScanLinkUsed() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "kybr.scanner", name = "mode", havingValue = "clamd")
        static final class LiveScanner {
        }

        @ConditionalOnProperty(prefix = "kybr.image", name = "mode", havingValue = "remote")
        static final class RemoteSanitizer {
        }
    }
}

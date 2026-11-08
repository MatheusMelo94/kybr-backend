package com.matheusmelo.kybr.sanitizer;

import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * The photo sanitizer: a second entry point in the API's image, started on the isolated scanner
 * machine with {@code java -cp application.jar com.matheusmelo.kybr.sanitizer.SanitizerApplication}.
 * Its context holds the sanitizer and its HTTP endpoint and nothing else: no auto-configuration, no
 * data store, no provider, no e-mail, no secret ({@link SanitizerGuard} refuses to start if any such
 * setting is present). It reads {@code sanitizer.*} settings ({@code SANITIZER_PORT}, ...) and never
 * the API's configuration files.
 *
 * <p>Deliberately not a Spring stereotype: the API scans every package below its own, and this
 * class must never be picked up there (an architecture rule enforces it).
 */
public class SanitizerApplication {

    static final int DEFAULT_PORT = 8090;

    public static void main(String[] args) {
        // the JDK server's own limits on a slow request or response
        System.setProperty("sun.net.httpserver.maxReqTime", System.getProperty("sun.net.httpserver.maxReqTime", "30"));
        System.setProperty("sun.net.httpserver.maxRspTime", System.getProperty("sun.net.httpserver.maxRspTime", "30"));
        application(new StandardEnvironment()).run(args);
    }

    /** The application over {@code environment}, checked by the guard before anything starts. */
    public static SpringApplication application(ConfigurableEnvironment environment) {
        SpringApplication application = new SpringApplication(SanitizerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setEnvironment(environment);
        application.setDefaultProperties(Map.of("spring.config.name", "sanitizer", "spring.main.banner-mode", "off"));
        application.addListeners(event -> {
            if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
                SanitizerGuard.check(prepared.getEnvironment());
            }
        });
        return application;
    }

    @Bean
    ImageSanitizer imageSanitizer(Environment environment) {
        ImageHeaderCheck headerCheck = new ImageHeaderCheck(
                environment.getProperty("sanitizer.max-pixels", Long.class, 50_000_000L),
                environment.getProperty("sanitizer.max-side", Integer.class, 12_000));
        return new ImageSanitizer(headerCheck, environment.getProperty("sanitizer.target-long-edge", Integer.class, 4_000));
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    SanitizerServer sanitizerServer(ImageSanitizer sanitizer, Environment environment) {
        return new SanitizerServer(
                environment.getProperty("sanitizer.port", Integer.class, DEFAULT_PORT),
                sanitizer::sanitize,
                environment.getProperty("sanitizer.budget", Duration.class, Duration.ofSeconds(15)),
                (int) environment.getProperty("sanitizer.max-request", DataSize.class, DataSize.ofMegabytes(12))
                        .toBytes());
    }
}

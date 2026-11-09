package com.matheusmelo.kybr.support.upload;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes {@link ScriptedScanner}, {@link ScriptedStorage} and {@link ScriptedPhotoSanitizer} the primary
 * scanner, storage and photo sanitizer, ahead of the stub, the in-memory store and the stand-in. Opt in with {@code @Import(UploadTestDoublesConfiguration.class)}
 * on a test base class; every class sharing that base shares one cached context and the same doubles,
 * so reset them between tests. Never scanned.
 */
@TestConfiguration(proxyBeanMethods = false)
public class UploadTestDoublesConfiguration {

    @Bean
    @Primary
    ScriptedScanner scriptedScanner() {
        return new ScriptedScanner();
    }

    @Bean
    @Primary
    ScriptedStorage scriptedStorage(Clock clock) {
        return new ScriptedStorage(clock);
    }

    @Bean
    @Primary
    ScriptedPhotoSanitizer scriptedPhotoSanitizer() {
        return new ScriptedPhotoSanitizer();
    }
}

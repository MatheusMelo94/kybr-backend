package com.matheusmelo.kybr.support.web;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The app as staging runs it: the {@code staging} profile on top of {@code test} (for the test
 * secrets and MongoDB), so HSTS, {@code Fly-Client-IP} trust and disabled API docs all apply. One
 * shared context for every subclass; don't add per-class configuration.
 */
@ActiveProfiles({"test", "staging"})
@TestPropertySource(properties = "springdoc.api-docs.enabled=false") // the test profile turns them on
public abstract class StagingWebIntegrationTest extends WebIntegrationTest {
}

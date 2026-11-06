package com.matheusmelo.kybr.acceptance;

import com.matheusmelo.kybr.support.web.WebIntegrationTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;

/**
 * Base for the cross-cutting acceptance suite on the
 * {@code test} profile. Shares {@link WebIntegrationTest}'s cached context (no new properties or
 * beans), and records every response through {@link ResponseInvariants}, checked after each test.
 */
@Tag("b0-foundation")
public abstract class AcceptanceTest extends WebIntegrationTest {

    protected final ResponseInvariants invariants = new ResponseInvariants();

    @Override
    protected RequestSpecification httpWithoutKey() {
        return super.httpWithoutKey().filter(invariants);
    }

    @AfterEach
    void everyResponseKeepsTheB0Invariants() {
        invariants.verifyAndReset();
    }
}

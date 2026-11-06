package com.matheusmelo.kybr.acceptance;

import com.matheusmelo.kybr.support.web.StagingWebIntegrationTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;

/**
 * Base for acceptance cases that need the app as staging runs it ({@code Fly-Client-IP}
 * trust, HSTS, ECS logs). Shares {@link StagingWebIntegrationTest}'s cached context.
 */
@Tag("b0-foundation")
public abstract class StagingAcceptanceTest extends StagingWebIntegrationTest {

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

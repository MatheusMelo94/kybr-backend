package com.matheusmelo.kybr.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/** Each image isolation rule catches a known violation, so none passes vacuously. */
class ImageIsolationRulesSelfTest {

    private static final String FIXTURE_SANITIZER = "com.matheusmelo.kybr.arch.fixture.sanitizer..";
    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(
            "com.matheusmelo.kybr.arch.fixture.service.image", "com.matheusmelo.kybr.arch.fixture.sanitizer");

    private static void assertReports(ArchRule rule, String detail) {
        EvaluationResult result = rule.evaluate(FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(line -> line.contains(detail));
    }

    @Test
    void anApiClassDecodingPixelsIsReported() {
        assertReports(ImageIsolationRulesTest.NO_IMAGE_DECODING_OUTSIDE_THE_SANITIZER, "ImageIO.read");
    }

    @Test
    void anApiClassReachingIntoTheSanitizerIsReported() {
        assertReports(ImageIsolationRulesTest.noDependencyOn(FIXTURE_SANITIZER), "SanitizerCaller");
    }

    @Test
    void aStereotypeInTheSanitizerIsReported() {
        assertReports(ImageIsolationRulesTest.neverComponentScanned(FIXTURE_SANITIZER), "SanitizerFixture$Scanned");
    }
}

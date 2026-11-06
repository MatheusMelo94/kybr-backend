package com.matheusmelo.kybr.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Each architecture rule catches a known violation, so none of them passes vacuously. */
class ArchitectureRulesSelfTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("com.matheusmelo.kybr.arch.fixture");

    static Stream<Arguments> rules() {
        return Stream.of(
                Arguments.of(ArchitectureRulesTest.CONTROLLERS_DO_NOT_USE_REPOSITORIES, "FixtureRepository"),
                Arguments.of(ArchitectureRulesTest.UTIL_HAS_NO_SPRING, "org.springframework.util.StringUtils"),
                Arguments.of(ArchitectureRulesTest.MODELS_DO_NOT_USE_DTOS, "FixtureDto"),
                Arguments.of(ArchitectureRulesTest.NO_REDIRECTS_COOKIES_OR_SESSIONS, "sendRedirect"),
                Arguments.of(ArchitectureRulesTest.NO_REDIRECTS_COOKIES_OR_SESSIONS, "addCookie"),
                Arguments.of(ArchitectureRulesTest.NO_REDIRECTS_COOKIES_OR_SESSIONS, "getSession"),
                Arguments.of(ArchitectureRulesTest.NO_COOKIE_READS, "getCookies"),
                Arguments.of(ArchitectureRulesTest.NO_COOKIE_READS, "CookieValue"),
                Arguments.of(ArchitectureRulesTest.NO_X_FORWARDED_FOR, "ViolatingFilter references"),
                Arguments.of(ArchitectureRulesTest.NO_CNPJ_CANONICALIZE_AT_BOUNDARY, "Cnpj.canonicalize"),
                Arguments.of(ArchitectureRulesTest.FILTERS_DO_NOT_ROUTE_ON_RAW_URI, "routesOnRawUri"));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void ruleReportsTheFixtureViolation(ArchRule rule, String expectedDetail) {
        EvaluationResult result = rule.evaluate(FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(detail -> detail.contains(expectedDetail));
    }
}

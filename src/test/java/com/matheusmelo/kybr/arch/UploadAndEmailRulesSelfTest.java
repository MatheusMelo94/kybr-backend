package com.matheusmelo.kybr.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.arch.fixture.upload.FixtureLiveClient;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/** Each upload and e-mail rule catches a known violation, so none passes vacuously. */
class UploadAndEmailRulesSelfTest {

    private static final JavaClasses FIXTURES =
            new ClassFileImporter().importPackages("com.matheusmelo.kybr.arch.fixture.upload");
    private static final Set<String> FIXTURE_LIVE_CLIENTS =
            Set.of(FixtureLiveClient.class.getName(), S3Client.class.getName());

    private static List<String> violations(ArchRule rule) {
        EvaluationResult result = rule.evaluate(FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        return result.getFailureReport().getDetails();
    }

    @Test
    void readingTheClientFilenameIsReported() {
        assertThat(violations(UploadAndEmailRulesTest.NO_CLIENT_FILENAMES))
                .anyMatch(line -> line.contains("getOriginalFilename"))
                .anyMatch(line -> line.contains("getSubmittedFileName"));
    }

    @Test
    void anUngatedLiveClientBeanIsReportedAndAGatedOneIsNot() {
        assertThat(violations(UploadAndEmailRulesTest.liveClientBeansAreModeGated(FIXTURE_LIVE_CLIENTS)))
                .anyMatch(line -> line.contains("Beans.unguarded()"))
                .anyMatch(line -> line.contains("UnguardedComponent"))
                .anyMatch(line -> line.contains("WrongModeComponent"))
                .noneMatch(line -> line.contains("Beans.guarded()"))
                .noneMatch(line -> line.contains("GuardedComponent is"));
    }

    @Test
    void buildingALiveClientOutsideConfigurationIsReported() {
        assertThat(violations(UploadAndEmailRulesTest.liveClientsAreBuiltOnlyByConfiguration(FIXTURE_LIVE_CLIENTS)))
                .anyMatch(line -> line.contains("LiveClientBuilder.client()"))
                .anyMatch(line -> line.contains("S3Client.builder()"))
                .noneMatch(line -> line.contains("LiveClientBeans$Beans"));
    }

    @Test
    void aSendReachedFromARetriedTransactionIsReported() {
        assertThat(violations(UploadAndEmailRulesTest.NO_EMAIL_SEND_INSIDE_TRANSACTION_RETRY))
                .anyMatch(line -> line.contains("SendingTransaction.commitAndNotify") && line.contains("EmailSender.send"));
    }
}

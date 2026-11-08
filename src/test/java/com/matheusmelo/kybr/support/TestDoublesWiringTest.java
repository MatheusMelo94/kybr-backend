package com.matheusmelo.kybr.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.email.EmailSender;
import com.matheusmelo.kybr.service.email.RecordingEmailSender;
import com.matheusmelo.kybr.service.scan.MalwareScanner;
import com.matheusmelo.kybr.service.scan.StubScanner;
import com.matheusmelo.kybr.service.storage.DocumentStorage;
import com.matheusmelo.kybr.service.storage.InMemoryDocumentStorage;
import com.matheusmelo.kybr.support.email.EmailTestDoublesConfiguration;
import com.matheusmelo.kybr.support.email.ScriptedEmailSender;
import com.matheusmelo.kybr.support.upload.ScriptedScanner;
import com.matheusmelo.kybr.support.upload.ScriptedStorage;
import com.matheusmelo.kybr.support.upload.UploadTestDoublesConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The non-production scanner, storage and sender exist outside prod in their modes; the scripted
 * doubles replace them when a test imports their configuration; prod never has any of them.
 */
class TestDoublesWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(StubScanner.class, InMemoryDocumentStorage.class, RecordingEmailSender.class);

    @Test
    void outsideProdTheDefaultModesWireTheStandIns() {
        runner.run(context -> {
            assertThat(context.getBean(MalwareScanner.class)).isInstanceOf(StubScanner.class);
            assertThat(context.getBean(DocumentStorage.class)).isInstanceOf(InMemoryDocumentStorage.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(RecordingEmailSender.class);
        });
    }

    @Test
    void theScriptedDoublesReplaceTheStandInsWhenImported() {
        runner.withUserConfiguration(UploadTestDoublesConfiguration.class, EmailTestDoublesConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(MalwareScanner.class)).isInstanceOf(ScriptedScanner.class);
                    assertThat(context.getBean(DocumentStorage.class)).isInstanceOf(ScriptedStorage.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(ScriptedEmailSender.class);
                });
    }

    @Test
    void theLiveModesWireNoStandIn() {
        runner.withPropertyValues("kybr.scanner.mode=clamd", "kybr.storage.mode=s3", "kybr.email.mode=resend")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MalwareScanner.class);
                    assertThat(context).doesNotHaveBean(DocumentStorage.class);
                    assertThat(context).doesNotHaveBean(EmailSender.class);
                });
    }

    @Test
    void prodNeverHasAStandInWhateverTheModesSay() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("kybr.scanner.mode=stub", "kybr.storage.mode=memory", "kybr.email.mode=recording")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StubScanner.class);
                    assertThat(context).doesNotHaveBean(InMemoryDocumentStorage.class);
                    assertThat(context).doesNotHaveBean(RecordingEmailSender.class);
                });
    }
}

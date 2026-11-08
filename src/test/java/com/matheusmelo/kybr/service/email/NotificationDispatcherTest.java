package com.matheusmelo.kybr.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationDispatcherTest {

    private static final NotificationDispatcher.Settings ON =
            new NotificationDispatcher.Settings(true, Duration.ofSeconds(60), List.of(Duration.ofMinutes(1)));

    @Test
    void switchingTheContactNoticeOnWithoutItsTemplateFailsStartup() {
        TemplateRenderer withoutIt = new TemplateRenderer("classpath*:email-fixtures/valid/*.txt");

        assertThatThrownBy(() -> new NotificationDispatcher(null, null, null, null, withoutIt, ON, Clock.systemUTC(),
                new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kybr.email.events.contato-alterado.enabled")
                .hasMessageContaining("contato-alterado");
    }

    @Test
    void theApplicationShipsNoTemplateForTheContactNoticeUntilItHasApprovedCopy() {
        assertThat(Path.of("src/main/resources/email", NotificationDispatcher.CONTATO_ALTERADO_TEMPLATE + ".txt"))
                .doesNotExist();
        assertThat(Path.of("pom.xml")).exists();
    }
}

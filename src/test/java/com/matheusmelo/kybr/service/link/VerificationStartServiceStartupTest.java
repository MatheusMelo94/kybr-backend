package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A missing dispatcher fails startup instead of leaving every case to the recovery poller. */
class VerificationStartServiceStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(LinkRateLimits.class, () -> mock(LinkRateLimits.class))
            .withBean(IdempotencyService.class, () -> mock(IdempotencyService.class))
            .withBean(LinkCaseRepository.class, () -> mock(LinkCaseRepository.class))
            .withBean(SellerUpserts.class, () -> mock(SellerUpserts.class))
            .withBean(AuditEventRepository.class, () -> mock(AuditEventRepository.class))
            .withBean(CaseTokens.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(VerificationStartService.class);

    @Test
    void startupFailsWithoutADispatcher() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining(VerificationDispatcher.class.getName());
        });
    }

    @Test
    void startupSucceedsWithADispatcher() {
        runner.withBean(VerificationDispatcher.class, () -> mock(VerificationDispatcher.class))
                .run(context -> assertThat(context).hasSingleBean(VerificationStartService.class));
    }
}

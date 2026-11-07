package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.SellerUpserts;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Rate limits are applied before anything is written. */
class VerificationStartServiceTest {

    private final LinkRateLimits limits = mock(LinkRateLimits.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final LinkCaseRepository cases = mock(LinkCaseRepository.class);
    private final SellerUpserts sellers = mock(SellerUpserts.class);
    private final AuditEventRepository audits = mock(AuditEventRepository.class);
    private final VerificationDispatcher dispatcher = mock(VerificationDispatcher.class);
    private final VerificationStartService service = new VerificationStartService(limits, idempotency, cases,
            sellers, audits, new CaseTokens(), dispatcher, Clock.systemUTC());

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"LIMITE_CNPJ", "LIMITE_TENTATIVAS"})
    void aDeniedRateLimitWritesNothing(ErrorCode code) {
        Workspace workspace = TestWorkspaces.builder().build();
        ClientIp ip = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);
        doThrow(new RateLimitExceededException(code, 60)).when(limits).verify(eq(ip), eq(workspace), anyString());
        StartCommand command = new StartCommand("11222333000181", "Maria Exemplo", null, null,
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.start(workspace, ip, command, null, token -> token))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(idempotency, cases, sellers, audits, dispatcher);
    }

    @ParameterizedTest
    @EnumSource(value = ClientIp.Source.class)
    void rateLimitsSeeTheCanonicalCnpj(ClientIp.Source source) {
        Workspace workspace = TestWorkspaces.builder().build();
        ClientIp ip = new ClientIp("198.51.100.4", source);
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_CNPJ, 1)).when(limits)
                .verify(any(), any(), eq("11222333000181"));

        assertThatThrownBy(() -> service.start(workspace, ip, new StartCommand("11222333000181", "Maria Exemplo",
                null, null, UUID.randomUUID().toString()), null, token -> token))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void aCnpjThatIsNotAlreadyCanonicalIsRefusedNotRewritten() {
        Workspace workspace = TestWorkspaces.builder().build();
        ClientIp ip = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);

        assertThatThrownBy(() -> service.start(workspace, ip, new StartCommand("11.222.333/0001-81", "Maria Exemplo",
                null, null, UUID.randomUUID().toString()), null, token -> token))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CNPJ_INVALIDO));

        verifyNoInteractions(limits, idempotency, cases, sellers, audits, dispatcher);
    }
}

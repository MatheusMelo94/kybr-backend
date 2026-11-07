package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.ContactChangeWrites;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.service.crypto.FieldCipher;
import com.matheusmelo.kybr.support.TestSecrets;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.mongodb.MongoException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/** The order of checks: IP limit, case, e-mail, decided, change limit. */
class ContactServiceTest {

    private static final ClientIp IP = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);

    private final LinkRateLimits limits = mock(LinkRateLimits.class);
    private final CaseReadService caseReads = mock(CaseReadService.class);
    private final LinkCaseRepository cases = mock(LinkCaseRepository.class);
    private final ContactChangeWrites writes = mock(ContactChangeWrites.class);
    private final AuditEventRepository audits = mock(AuditEventRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ContactService service = new ContactService(limits, caseReads, cases, writes, audits,
            new FieldCipher(TestSecrets.PII_ENC_KEY), mock(PlatformTransactionManager.class), Clock.systemUTC(),
            meters);
    private final Workspace workspace = TestWorkspaces.builder().build();

    @Test
    void theIpLimitPrecedesTheCaseLookup() {
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 60)).when(limits).contact(IP, workspace);

        assertThatThrownBy(() -> service.change(workspace, IP, TestCases.newToken(), "a@b.co"))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(caseReads, cases, writes, audits);
    }

    @Test
    void anUnknownCasePrecedesEmailValidation() {
        when(caseReads.find(any(), any())).thenThrow(new ApiException(ErrorCode.CASO_NAO_ENCONTRADO));

        assertThatThrownBy(() -> service.change(workspace, IP, TestCases.newToken(), "not an e-mail"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCaso.class, names = {"approved", "blocked"})
    void anInvalidEmailOnADecidedCaseIs422AndAValidOneIs409(EstadoCaso estado) {
        TestCases.Built built = TestCases.builder(workspace).estado(estado).build();
        when(caseReads.find(workspace, built.token())).thenReturn(built.linkCase());

        assertThatThrownBy(() -> service.change(workspace, IP, built.token(), "a@@b.co"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_INVALIDO);
                    assertThat(e.field()).isEqualTo("email");
                });
        assertThatThrownBy(() -> service.change(workspace, IP, built.token(), "a@b.co"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_DECIDIDO));
        verify(limits, never()).contactChange(any(UUID.class));
        verifyNoInteractions(writes, audits);
        assertThat(meters.counter("kybr.link.contact", "resultado", "decided").count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCaso.class, names = {"approved", "blocked"})
    void aCaseDecidedAfterTheCheckIsStill409AndNothingIsWritten(EstadoCaso estado) {
        TestCases.Built built = TestCases.builder(workspace).build();
        TestCases.Built decided = TestCases.builder(workspace).id(built.linkCase().id()).estado(estado).build();
        when(caseReads.find(workspace, built.token())).thenReturn(built.linkCase());
        when(cases.findById(built.linkCase().id())).thenReturn(Optional.of(decided.linkCase()));

        assertThatThrownBy(() -> service.change(workspace, IP, built.token(), "a@b.co"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_DECIDIDO));

        verifyNoInteractions(writes, audits);
        assertThat(meters.counter("kybr.link.contact", "resultado", "decided").count()).isEqualTo(1);
    }

    @Test
    void aMissingEmailIs422() {
        TestCases.Built built = TestCases.builder(workspace).build();
        when(caseReads.find(workspace, built.token())).thenReturn(built.linkCase());
        when(cases.findById(built.linkCase().id())).thenReturn(Optional.of(built.linkCase()));

        assertThatThrownBy(() -> service.change(workspace, IP, built.token(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_INVALIDO));
    }

    @Test
    void theChangeLimitPrecedesAnyWrite() {
        TestCases.Built built = TestCases.builder(workspace).build();
        when(caseReads.find(workspace, built.token())).thenReturn(built.linkCase());
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 60)).when(limits)
                .contactChange(built.linkCase().id());

        assertThatThrownBy(() -> service.change(workspace, IP, built.token(), "a@b.co"))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(writes, audits);
        assertThat(meters.counter("kybr.link.contact", "resultado", "limited").count()).isEqualTo(1);
    }

    @Test
    void aWriteConflictThatRepeatsUnderContentionIsRetriedUntilTheChangeCommits() {
        TestCases.Built built = TestCases.builder(workspace).build();
        when(caseReads.find(workspace, built.token())).thenReturn(built.linkCase());
        when(cases.findById(built.linkCase().id())).thenReturn(Optional.of(built.linkCase()));
        AtomicInteger conflicts = new AtomicInteger();
        doAnswer(call -> {
            if (conflicts.incrementAndGet() <= 4) {
                MongoException conflict = new MongoException(112, "synthetic write conflict");
                conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                throw new DataIntegrityViolationException("synthetic", conflict);
            }
            return null;
        }).when(writes).setContato(any(), any());

        service.change(workspace, IP, built.token(), "a@b.co");

        assertThat(conflicts).hasValue(5);
        verify(audits).insert(any(AuditEvent.class));
        assertThat(meters.counter("kybr.link.contact", "resultado", "changed").count()).isEqualTo(1);
    }
}

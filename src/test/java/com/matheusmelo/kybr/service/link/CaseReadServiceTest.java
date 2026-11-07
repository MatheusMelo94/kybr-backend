package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.RateLimitExceededException;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaseReadServiceTest {

    private final LinkCaseRepository repository = mock(LinkCaseRepository.class);
    private final LinkRateLimits limits = mock(LinkRateLimits.class);
    private final CaseReadService service = new CaseReadService(repository, limits);
    private final Workspace workspace = TestWorkspaces.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"short", "a+aaaaaaaaaaaaaaaaaaaaaa", "..aaaaaaaaaaaaaaaaaaaaaa"})
    void anOffPatternTokenIsCaseNotFoundWithoutAQuery(String token) {
        assertCaseNotFound(() -> service.find(workspace, token));
        assertCaseNotFound(() -> service.find(workspace, null));
        verifyNoInteractions(repository);
    }

    @Test
    void anUnknownTokenIsCaseNotFoundAfterOneQueryOnHashAndWorkspace() {
        String token = TestCases.newToken();
        when(repository.findByTokenHashAndWorkspaceId(anyString(), any(UUID.class))).thenReturn(Optional.empty());

        assertCaseNotFound(() -> service.find(workspace, token));

        verify(repository, times(1)).findByTokenHashAndWorkspaceId(TestCases.hash(token), workspace.id());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aKnownTokenReturnsItsCase() {
        TestCases.Built built = TestCases.builder(workspace).build();
        when(repository.findByTokenHashAndWorkspaceId(TestCases.hash(built.token()), workspace.id()))
                .thenReturn(Optional.of(built.linkCase()));

        LinkCase found = service.find(workspace, built.token());

        assertThat(found).isEqualTo(built.linkCase());
        verify(repository, times(1)).findByTokenHashAndWorkspaceId(anyString(), any(UUID.class));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aThrottledReadMakesNoCaseQuery() {
        ClientIp ip = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 30)).when(limits).caseRead(ip, workspace);

        assertThatThrownBy(() -> service.read(workspace, ip, TestCases.newToken()))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void aReadChecksTheLimitThenLooksUpTheToken() {
        ClientIp ip = new ClientIp("198.51.100.4", ClientIp.Source.FORWARDED);
        TestCases.Built built = TestCases.builder(workspace).build();
        when(repository.findByTokenHashAndWorkspaceId(TestCases.hash(built.token()), workspace.id()))
                .thenReturn(Optional.of(built.linkCase()));

        assertThat(service.read(workspace, ip, built.token())).isEqualTo(built.linkCase());

        InOrder order = inOrder(limits, repository);
        order.verify(limits).caseRead(ip, workspace);
        order.verify(repository).findByTokenHashAndWorkspaceId(anyString(), any(UUID.class));
    }

    private static void assertCaseNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO);
            assertThat(e.field()).isNull();
        });
    }
}

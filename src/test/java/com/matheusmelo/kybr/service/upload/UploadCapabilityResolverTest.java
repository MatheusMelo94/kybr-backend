package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.CodigoResultado;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.ItemResultado;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.link.Resultado;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.DocumentRequestRepository;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.service.link.CaseReadService;
import com.matheusmelo.kybr.service.link.LinkRateLimits;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UploadCapabilityResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    private final LinkCaseRepository cases = mock(LinkCaseRepository.class);
    private final DocumentRequestRepository requests = mock(DocumentRequestRepository.class);
    private final UploadCapabilityResolver resolver = new UploadCapabilityResolver(
            new CaseReadService(cases, mock(LinkRateLimits.class)), cases, requests, Clock.fixed(NOW, ZoneOffset.UTC));
    private final Workspace workspace = TestWorkspaces.builder().build();

    @BeforeEach
    void nothingFoundByDefault() {
        when(cases.findByTokenHashAndWorkspaceId(anyString(), any(UUID.class))).thenReturn(Optional.empty());
        when(requests.findByTokenHash(anyString())).thenReturn(Optional.empty());
        when(cases.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private static ItemResultado documentItem(CodigoResultado codigo, DocumentoTipo tipo) {
        return new ItemResultado(codigo, null, null, null, null, tipo, null);
    }

    private static Resultado pendingWith(ItemResultado... itens) {
        return new Resultado("EMPRESA EXEMPLO LTDA", NOW, null, NOW.plus(Duration.ofDays(2)), null, List.of(itens), null);
    }

    private TestCases.Built storedCase(TestCases.Builder builder) {
        TestCases.Built built = builder.build();
        when(cases.findByTokenHashAndWorkspaceId(TestCases.hash(built.token()), workspace.id()))
                .thenReturn(Optional.of(built.linkCase()));
        when(cases.findById(built.linkCase().id())).thenReturn(Optional.of(built.linkCase()));
        return built;
    }

    private void assertNotFound(String token) {
        assertThatThrownBy(() -> resolver.resolve(workspace, token)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CASO_NAO_ENCONTRADO));
    }

    @Test
    void aPendingCaseAllowsTheTypesOfItsDocumentItems() {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending).resultado(pendingWith(
                documentItem(CodigoResultado.DOCUMENTO_RESPONSAVEL, DocumentoTipo.RESPONSAVEL),
                documentItem(CodigoResultado.DOCUMENTO_SOLICITADO, DocumentoTipo.CARTAO_CNPJ),
                ItemResultado.of(CodigoResultado.IE_NAO_ENCONTRADA))));

        UploadCapability capability = resolver.resolve(workspace, built.token());

        assertThat(capability.origem()).isEqualTo(UploadCapability.Origem.LINK_TOKEN);
        assertThat(capability.linkCase()).isEqualTo(built.linkCase());
        assertThat(capability.caseId()).isEqualTo(built.linkCase().id());
        assertThat(capability.workspaceId()).isEqualTo(workspace.id());
        assertThat(capability.documentRequestId()).isNull();
        assertThat(capability.tiposPermitidos()).containsExactlyInAnyOrder(DocumentoTipo.RESPONSAVEL, DocumentoTipo.CARTAO_CNPJ);
    }

    @Test
    void aPendingCaseWithoutDocumentItemsAllowsNothing() {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .resultado(pendingWith(ItemResultado.of(CodigoResultado.EMPRESA_RECENTE))));

        assertThat(resolver.resolve(workspace, built.token()).tiposPermitidos()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCaso.class, names = {"processing", "approved", "blocked"})
    void aCaseOutsidePendingAllowsNothing(EstadoCaso estado) {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(estado).resultado(pendingWith(
                documentItem(CodigoResultado.DOCUMENTO_RESPONSAVEL, DocumentoTipo.RESPONSAVEL))));

        assertThat(resolver.resolve(workspace, built.token()).tiposPermitidos()).isEmpty();
    }

    @Test
    void aRevokedLinkTokenIsCaseNotFound() {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .tokenRevogadoEm(NOW.minusSeconds(60)).resultado(pendingWith(
                        documentItem(CodigoResultado.DOCUMENTO_RESPONSAVEL, DocumentoTipo.RESPONSAVEL))));

        assertNotFound(built.token());
    }

    @Test
    void anUnknownOrMalformedTokenIsCaseNotFound() {
        assertNotFound(TestCases.newToken());
        assertNotFound("short");
        assertNotFound(null);
    }

    @Test
    void aMalformedTokenMakesNoQuery() {
        assertNotFound("not a token");
        verifyNoInteractions(requests);
    }

    private DocumentRequest request(UUID caseId, UUID workspaceId, String token, Instant expira, Instant revogado,
            Instant encerrado) {
        DocumentRequest request = new DocumentRequest(UUID.randomUUID(), workspaceId, caseId, TestCases.hash(token),
                List.of(DocumentoTipo.PROCURACAO, DocumentoTipo.COMPROVANTE_ENDERECO), NOW.minus(Duration.ofDays(1)),
                expira, revogado, encerrado);
        when(requests.findByTokenHash(TestCases.hash(token))).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    void anOpenDocumentRequestAllowsItsTypes() {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending));
        String token = TestCases.newToken();
        DocumentRequest request = request(built.linkCase().id(), workspace.id(), token, NOW.plus(Duration.ofDays(29)), null,
                null);

        UploadCapability capability = resolver.resolve(workspace, token);

        assertThat(capability.origem()).isEqualTo(UploadCapability.Origem.DOCUMENT_REQUEST);
        assertThat(capability.documentRequestId()).isEqualTo(request.id());
        assertThat(capability.linkCase()).isEqualTo(built.linkCase());
        assertThat(capability.tiposPermitidos())
                .containsExactlyInAnyOrder(DocumentoTipo.PROCURACAO, DocumentoTipo.COMPROVANTE_ENDERECO);
    }

    @Test
    void aRevokedClosedExpiredOrForeignDocumentRequestIsCaseNotFound() {
        TestCases.Built built = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending));
        UUID caseId = built.linkCase().id();
        String revoked = TestCases.newToken();
        String closed = TestCases.newToken();
        String expired = TestCases.newToken();
        String expiringNow = TestCases.newToken();
        String foreign = TestCases.newToken();
        request(caseId, workspace.id(), revoked, NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(1), null);
        request(caseId, workspace.id(), closed, NOW.plus(Duration.ofDays(1)), null, NOW.minusSeconds(1));
        request(caseId, workspace.id(), expired, NOW.minusSeconds(1), null, null);
        request(caseId, workspace.id(), expiringNow, NOW, null, null);
        request(caseId, UUID.randomUUID(), foreign, NOW.plus(Duration.ofDays(1)), null, null);

        assertNotFound(revoked);
        assertNotFound(closed);
        assertNotFound(expired);
        assertNotFound(expiringNow);
        assertNotFound(foreign);
    }

    @Test
    void aDocumentRequestForACaseWhoseTokenWasRevokedIsCaseNotFound() {
        LinkCase revoked = storedCase(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .tokenRevogadoEm(NOW.minusSeconds(60))).linkCase();
        String token = TestCases.newToken();
        request(revoked.id(), workspace.id(), token, NOW.plus(Duration.ofDays(1)), null, null);

        assertNotFound(token);
    }
}

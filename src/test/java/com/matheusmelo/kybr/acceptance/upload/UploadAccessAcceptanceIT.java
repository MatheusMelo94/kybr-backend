package com.matheusmelo.kybr.acceptance.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.acceptance.upload.fixtures.SampleFiles;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentRequest;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Who may upload: a live case token of an open link, or an open document-request token for the
 * types it lists. Every other token (unknown, of another link, revoked, expired, closed) gets the
 * same 404 as an unknown one, byte for byte, so a token's existence never leaks. A document-request
 * token opens the upload only, never the case read or the contact change.
 */
class UploadAccessAcceptanceIT extends UploadAcceptanceTest {

    private static final DocumentoTipo CONTRATO = DocumentoTipo.CONTRATO_SOCIAL_OU_CCMEI;
    private static final DocumentoTipo CARTAO = DocumentoTipo.CARTAO_CNPJ;

    private Response unknownToken() {
        return upload(uploadPath(workspace.slug(), TestCases.newToken()), TIPO.name(), SampleFiles.photo("x"));
    }

    private static Set<String> headerNames(Response response) {
        Set<String> names = new TreeSet<>();
        response.headers().asList().forEach(h -> names.add(h.getName().toLowerCase()));
        names.remove("date");
        return names;
    }

    private void assertSameAsAnUnknownToken(Response response) {
        Response unknown = unknownToken();
        assertError(response, 404, "CASO_NAO_ENCONTRADO");
        assertThat(response.asString()).isEqualTo(unknown.asString());
        assertThat(headerNames(response)).isEqualTo(headerNames(unknown));
    }

    private String documentRequest(List<DocumentoTipo> tipos, Instant expiraEm, Instant revogadoEm, Instant encerradoEm) {
        String token = TestCases.newToken();
        mongo.insert(new DocumentRequest(UUID.randomUUID(), workspace.id(), pending.linkCase().id(), TestCases.hash(token),
                tipos, Instant.now(), expiraEm, revogadoEm, encerradoEm));
        return token;
    }

    private String openDocumentRequest() {
        return documentRequest(List.of(CONTRATO, CARTAO), Instant.now().plus(Duration.ofDays(30)), null, null);
    }

    // --- case tokens -----------------------------------------------------------------------------

    @Test
    void aRevokedCaseTokenIsAnUnknownToken() {
        TestCases.Built revoked = TestCases.builder(workspace).estado(EstadoCaso.pending)
                .resultado(stored(pending.linkCase().id()).resultado()).tokenRevogadoEm(Instant.now()).build();
        mongo.insert(revoked.linkCase());

        assertSameAsAnUnknownToken(upload(revoked, TIPO, SampleFiles.photo("x")));
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void aTokenOfAnotherLinkIsAnUnknownToken() {
        Workspace other = TestWorkspaces.builder().build();
        mongo.insert(other);

        assertSameAsAnUnknownToken(
                upload(uploadPath(other.slug(), pending.token()), TIPO.name(), SampleFiles.photo("x")));
    }

    @Test
    void aDisabledLinkRefusesUploadsAsAnUnknownLink() {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(workspace.id())), Update.update("link_ativo", false),
                Workspace.class);

        Response response = upload(pending, TIPO, SampleFiles.photo("x"));

        assertError(response, 404, "LINK_NAO_ENCONTRADO");
        assertThat(response.asString()).isEqualTo(
                upload(uploadPath("loja-inexistente", pending.token()), TIPO.name(), SampleFiles.photo("x")).asString());
        assertThat(storage.keys()).isEmpty();
    }

    // --- document-request tokens -----------------------------------------------------------------

    @Test
    void anOpenDocumentRequestAcceptsEachListedTypeWithItsOwnLimitAndNothingElse() {
        String token = openDocumentRequest();

        assertReceived(upload(uploadPath(workspace.slug(), token), CONTRATO.name(), SampleFiles.pdfOfExactSize(TEN_MIB)),
                CONTRATO, 1);
        assertReceived(upload(uploadPath(workspace.slug(), token), CARTAO.name(), SampleFiles.pdfOfExactSize(TEN_MIB)),
                CARTAO, 1);
        assertError(upload(uploadPath(workspace.slug(), token), TIPO.name(), SampleFiles.photo("fora")), 415,
                "FORMATO_INVALIDO");
    }

    @Test
    void aFileSentWithADocumentRequestTokenIsTiedToThatRequest() {
        String token = openDocumentRequest();
        DocumentRequest request = mongo.findOne(Query.query(Criteria.where(DocumentRequest.TOKEN_HASH)
                .is(TestCases.hash(token))), DocumentRequest.class);

        assertReceived(upload(uploadPath(workspace.slug(), token), CARTAO.name(), SampleFiles.photo("pedido")), CARTAO, 1);

        assertThat(documentsOf(pending.linkCase().id())).extracting(Document::documentRequestId)
                .containsExactly(request.id());
    }

    @Test
    void expiredRevokedAndClosedDocumentRequestsAreUnknownTokens() {
        Instant now = Instant.now();
        Instant later = now.plus(Duration.ofDays(30));
        List<String> tokens = List.of(
                documentRequest(List.of(CARTAO), now.minusSeconds(1), null, null),
                documentRequest(List.of(CARTAO), later, now, null),
                documentRequest(List.of(CARTAO), later, null, now));
        for (String token : tokens) {
            assertSameAsAnUnknownToken(upload(uploadPath(workspace.slug(), token), CARTAO.name(), SampleFiles.photo("x")));
        }
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    void aDocumentRequestTokenNeitherReadsTheCaseNorChangesTheContact() {
        String token = openDocumentRequest();

        assertError(readCase(workspace.slug(), token), 404, "CASO_NAO_ENCONTRADO");
        assertError(putEmail(workspace.slug(), token, "vendedor@example.test"), 404, "CASO_NAO_ENCONTRADO");
        assertThat(stored(pending.linkCase().id()).contato()).isNull();
    }
}

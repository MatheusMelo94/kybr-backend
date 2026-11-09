package com.matheusmelo.kybr.model.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What D4 shows for a document type, from the type's files. */
class DocumentItemStateTest {

    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final UUID FIRST_UPLOAD = UUID.randomUUID();
    private static final UUID SECOND_UPLOAD = UUID.randomUUID();

    @Test
    void nothingSentHasNoState() {
        assertThat(DocumentItemState.of(List.of())).isEmpty();
    }

    @Test
    void aFileStillBeingCheckedIsVerificandoAndCounts() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.RECEBIDO, null, FIRST_UPLOAD, T0))).orElseThrow();

        assertThat(state).isEqualTo(new DocumentItemState(SituacaoEnvio.verificando, 1, 0, null, FormatoEnvio.fotos));
    }

    @Test
    void oneAcceptedAndOneBeingCheckedIsStillVerificando() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.ACEITO, null, FIRST_UPLOAD, T0),
                file(EstadoDocumento.VERIFICANDO, null, SECOND_UPLOAD, T0.plusSeconds(60)))).orElseThrow();

        assertThat(state.situacao()).isEqualTo(SituacaoEnvio.verificando);
        assertThat(state.enviados()).isEqualTo(2);
    }

    @Test
    void everyFileAcceptedIsAceito() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.ACEITO, null, FIRST_UPLOAD, T0),
                file(EstadoDocumento.ACEITO, null, FIRST_UPLOAD, T0))).orElseThrow();

        assertThat(state).isEqualTo(new DocumentItemState(SituacaoEnvio.aceito, 2, 0, null, FormatoEnvio.fotos));
    }

    @Test
    void aRefusalInTheLatestUploadIsRecusadoWithItsReasonAndTheRefusedFileDoesntCount() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.ACEITO, null, FIRST_UPLOAD, T0),
                file(EstadoDocumento.ACEITO, null, SECOND_UPLOAD, T0.plusSeconds(60)),
                file(EstadoDocumento.RECUSADO, MotivoRecusa.CONTEUDO_NAO_PERMITIDO, SECOND_UPLOAD,
                        T0.plusSeconds(60)))).orElseThrow();

        assertThat(state).isEqualTo(new DocumentItemState(SituacaoEnvio.recusado, 2, 1,
                MotivoRecusa.CONTEUDO_NAO_PERMITIDO, FormatoEnvio.fotos));
    }

    @Test
    void aRefusalInAnEarlierUploadFollowedByAnAcceptedOneIsAceitoButStillCounted() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.RECUSADO, MotivoRecusa.VERIFICACAO_FALHOU, FIRST_UPLOAD, T0),
                file(EstadoDocumento.ACEITO, null, SECOND_UPLOAD, T0.plusSeconds(60)))).orElseThrow();

        assertThat(state).isEqualTo(new DocumentItemState(SituacaoEnvio.aceito, 1, 1, null, FormatoEnvio.fotos));
    }

    @Test
    void everyFileRefusedLeavesNoFormat() {
        DocumentItemState state = DocumentItemState.of(List.of(
                file(EstadoDocumento.RECUSADO, MotivoRecusa.VERIFICACAO_FALHOU, FIRST_UPLOAD, T0))).orElseThrow();

        assertThat(state).isEqualTo(new DocumentItemState(SituacaoEnvio.recusado, 0, 1,
                MotivoRecusa.VERIFICACAO_FALHOU, null));
    }

    @Test
    void aRecordWrittenBeforeTheScanJobCountsAsAccepted() {
        Document legacy = new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                DocumentoTipo.RESPONSAVEL, FormatoEnvio.pdf, 1, "w/c/d", "application/pdf", 10, 10, "a", "b", T0);
        Document withoutState = new Document(legacy.id(), legacy.workspaceId(), legacy.caseId(), null, legacy.tipo(),
                legacy.formato(), 1, legacy.storageKey(), legacy.contentType(), 10, 10L, "a", "b", T0, null, null, null,
                null, null, 0, null, null, null);

        assertThat(DocumentItemState.of(List.of(withoutState))).contains(
                new DocumentItemState(SituacaoEnvio.aceito, 1, 0, null, FormatoEnvio.pdf));
    }

    private static Document file(EstadoDocumento estado, MotivoRecusa motivo, UUID envio, Instant criadoEm) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, DocumentoTipo.RESPONSAVEL,
                FormatoEnvio.fotos, 1, null, "image/jpeg", 10, null, UUID.randomUUID().toString(), null, criadoEm,
                estado, motivo, null, envio, estado == EstadoDocumento.RECUSADO ? null : true, 0, null, null, null);
    }
}

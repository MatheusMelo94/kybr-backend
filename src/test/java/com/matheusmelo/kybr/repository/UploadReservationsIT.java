package com.matheusmelo.kybr.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.UploadReservations.Result;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Per-type byte and per-case file reservations, as one conditional update on the case. */
class UploadReservationsIT extends MongoIntegrationTest {

    private static final long MAX = 10_485_760L;
    private static final DocumentoTipo TIPO = DocumentoTipo.RESPONSAVEL;

    @Autowired
    private UploadReservations reservations;

    @Autowired
    private LinkCaseRepository cases;

    private final Workspace workspace = TestWorkspaces.builder().build();

    private LinkCase saved(Map<DocumentoTipo, DocumentosTipo> documentos) {
        return cases.save(TestCases.builder(workspace).estado(EstadoCaso.pending).documentos(documentos).build()
                .linkCase());
    }

    private LinkCase reload(LinkCase linkCase) {
        return cases.findById(linkCase.id()).orElseThrow();
    }

    @Test
    void exactlyTheTypeLimitIsReservedAndCounted() {
        LinkCase linkCase = saved(Map.of());

        assertThat(reservations.reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, MAX, 3)).isEqualTo(Result.RESERVED);

        LinkCase after = reload(linkCase);
        assertThat(after.documentos().get(TIPO)).isEqualTo(new DocumentosTipo(FormatoEnvio.fotos, 3, MAX));
        assertThat(after.arquivosTotal()).isEqualTo(3);
    }

    @Test
    void oneByteOverTheTypeLimitIsTooLarge() {
        LinkCase empty = saved(Map.of());
        LinkCase partial = saved(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, MAX - 10)));

        assertThat(reservations.reserve(empty.id(), TIPO, FormatoEnvio.fotos, MAX + 1, 1)).isEqualTo(Result.ARQUIVO_GRANDE);
        assertThat(reservations.reserve(partial.id(), TIPO, FormatoEnvio.fotos, 11, 1)).isEqualTo(Result.ARQUIVO_GRANDE);
        assertThat(reservations.reserve(partial.id(), TIPO, FormatoEnvio.fotos, 10, 1)).isEqualTo(Result.RESERVED);
        assertThat(reload(empty).documentos()).isEmpty();
    }

    @Test
    void theThirtyFirstFileOfACaseIsOverTheFileLimit() {
        LinkCase linkCase = saved(Map.of(
                DocumentoTipo.CARTAO_CNPJ, new DocumentosTipo(FormatoEnvio.fotos, 20, 1000),
                TIPO, new DocumentosTipo(FormatoEnvio.fotos, 9, 1000)));

        assertThat(reservations.reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, 10, 2)).isEqualTo(Result.LIMITE_ARQUIVOS);
        assertThat(reservations.reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, 10, 1)).isEqualTo(Result.RESERVED);
        assertThat(reservations.reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, 10, 1)).isEqualTo(Result.LIMITE_ARQUIVOS);
        assertThat(reload(linkCase).arquivosTotal()).isEqualTo(30);
    }

    @Test
    void theFormatIsCheckedAgainWithTheReservation() {
        LinkCase photos = saved(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 1, 100)));
        LinkCase pdf = saved(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.pdf, 1, 100)));

        assertThat(reservations.reserve(photos.id(), TIPO, FormatoEnvio.pdf, 10, 1)).isEqualTo(Result.FORMATO);
        assertThat(reservations.reserve(pdf.id(), TIPO, FormatoEnvio.fotos, 10, 1)).isEqualTo(Result.FORMATO);
        assertThat(reservations.reserve(pdf.id(), TIPO, FormatoEnvio.pdf, 10, 1)).isEqualTo(Result.FORMATO);
        assertThat(reservations.reserve(photos.id(), DocumentoTipo.PROCURACAO, FormatoEnvio.pdf, 10, 1))
                .isEqualTo(Result.RESERVED);
    }

    @Test
    void aRevokedOrMissingCaseIsNotFound() {
        LinkCase revoked = cases.save(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .tokenRevogadoEm(Instant.now()).build().linkCase());

        assertThat(reservations.reserve(revoked.id(), TIPO, FormatoEnvio.fotos, 10, 1))
                .isEqualTo(Result.CASO_NAO_ENCONTRADO);
        assertThat(reservations.reserve(UUID.randomUUID(), TIPO, FormatoEnvio.fotos, 10, 1))
                .isEqualTo(Result.CASO_NAO_ENCONTRADO);
    }

    @Test
    void theReleaseRestoresTheCountersExactly() {
        LinkCase empty = saved(Map.of());
        LinkCase partial = saved(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 2, 500)));

        reservations.reserve(empty.id(), TIPO, FormatoEnvio.pdf, 300, 1);
        reservations.release(empty.id(), TIPO, 300, 1);
        reservations.reserve(partial.id(), TIPO, FormatoEnvio.fotos, 300, 2);
        reservations.release(partial.id(), TIPO, 300, 2);

        assertThat(reload(empty).documentos()).isEmpty();
        assertThat(reload(empty).arquivosTotal()).isZero();
        assertThat(reload(partial).documentos()).isEqualTo(partial.documentos());
        assertThat(reload(partial).arquivosTotal()).isEqualTo(2);
    }

    @Test
    void twoConcurrentReservationsThatTogetherExceedTheLimitHaveExactlyOneWinner() throws Exception {
        LinkCase linkCase = saved(Map.of());
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(contenders)) {
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return reservations.reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, MAX / 2 + 1, 1);
                }));
            }
            start.countDown();
            List<Result> outcomes = new ArrayList<>();
            for (Future<Result> result : results) {
                outcomes.add(result.get());
            }

            assertThat(outcomes).filteredOn(Result.RESERVED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(Result.ARQUIVO_GRANDE::equals).hasSize(contenders - 1);
        }
        assertThat(reload(linkCase).documentos().get(TIPO).bytes()).isEqualTo(MAX / 2 + 1);
    }
}

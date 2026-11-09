package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.UploadReservations.Result;
import com.matheusmelo.kybr.repository.UploadReservations;
import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import com.matheusmelo.kybr.service.link.LinkService;
import com.matheusmelo.kybr.support.link.TestCases;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The upload's checks up to the reservation, in order, each final. */
class UploadServicePrepareTest {

    private static final ClientIp IP = new ClientIp("198.51.100.4", ClientIp.Source.DIRECT);
    private static final DocumentoTipo TIPO = DocumentoTipo.RESPONSAVEL;

    private final UploadRateLimits limits = mock(UploadRateLimits.class);
    private final LinkService links = mock(LinkService.class);
    private final UploadCapabilityResolver resolver = mock(UploadCapabilityResolver.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final UploadReservations reservations = mock(UploadReservations.class);
    private final UploadConcurrency concurrency = new UploadConcurrency(2);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UploadService service = new UploadService(limits, links, resolver, documents, reservations,
            concurrency, mock(UploadCommit.class), new ImageHeaderCheck(50_000_000, 12_000), meters);

    private final Workspace workspace = TestWorkspaces.builder().build();
    private final String token = TestCases.newToken();
    private LinkCase linkCase;

    @BeforeEach
    void anOpenLinkAndAPendingCaseAskingForTheResponsiblePersonsDocument() {
        when(links.resolveActive(workspace.slug())).thenReturn(workspace);
        linkCase = TestCases.builder(workspace).estado(EstadoCaso.pending).build().linkCase();
        allow(linkCase, TIPO);
        when(reservations.reserve(any(), any(), any(), anyLong(), anyInt())).thenReturn(Result.RESERVED);
    }

    private void allow(LinkCase target, DocumentoTipo... tipos) {
        when(resolver.resolve(workspace, token))
                .thenReturn(new UploadCapability(target, Set.of(tipos), UploadCapability.Origem.LINK_TOKEN, null));
    }

    private UploadCommand command(FakeParts body) {
        return new UploadCommand(workspace.slug(), token, IP, body);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private double count(String resultado) {
        return meters.counter(UploadService.METRIC, "resultado", resultado).count();
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private void assertNothingParsedOrReserved(FakeParts body) {
        assertThat(body.parses).hasValue(0);
        verify(reservations, never()).reserve(any(), any(), any(), anyLong(), anyInt());
        assertThat(concurrency.available()).isEqualTo(2);
    }

    // --- before the body is read ---------------------------------------------------------------

    @Test
    void aThrottledIpIsRefusedBeforeAnyLookup() {
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 60)).when(limits).request(IP, workspace.slug());
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.LIMITE_TENTATIVAS);

        verifyNoInteractions(links, resolver, documents);
        assertNothingParsedOrReserved(body);
        assertThat(count("limitado")).isEqualTo(1);
    }

    @Test
    void anUnknownOrDisabledLinkIsNotFoundBeforeTheTokenIsLookedUp() {
        when(links.resolveActive(workspace.slug())).thenThrow(new ApiException(ErrorCode.LINK_NAO_ENCONTRADO));
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.LINK_NAO_ENCONTRADO);

        verifyNoInteractions(resolver);
        assertNothingParsedOrReserved(body);
        assertThat(count("nao_encontrado")).isEqualTo(1);
    }

    @Test
    void anUnknownTokenIsCaseNotFoundBeforeTheBodyIsRead() {
        when(resolver.resolve(workspace, token)).thenThrow(new ApiException(ErrorCode.CASO_NAO_ENCONTRADO));
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.CASO_NAO_ENCONTRADO);

        assertNothingParsedOrReserved(body);
        verify(limits, never()).perCase(any());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCaso.class, names = {"approved", "blocked"})
    void aDecidedCaseIsAConflictBeforeThePerCaseLimit(EstadoCaso estado) {
        allow(TestCases.builder(workspace).estado(estado).build().linkCase());
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.CASO_DECIDIDO);

        verify(limits, never()).perCase(any());
        assertNothingParsedOrReserved(body);
        assertThat(count("decidido")).isEqualTo(1);
    }

    @Test
    void aThrottledCaseIsRefusedBeforeTheBodyIsRead() {
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 600)).when(limits).perCase(linkCase.id());
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.LIMITE_TENTATIVAS);

        assertNothingParsedOrReserved(body);
    }

    @Test
    void aBusyMachineIsRefusedWithRetryAfterBeforeTheBodyIsRead() {
        UploadConcurrency.Permit a = concurrency.enter();
        UploadConcurrency.Permit b = concurrency.enter();
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertThatThrownBy(() -> service.prepare(command(body))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.INDISPONIVEL);
            assertThat(e.headers()).containsEntry("Retry-After", "5");
        });

        assertThat(body.parses).hasValue(0);
        assertThat(count("indisponivel")).isEqualTo(1);
        a.close();
        b.close();
    }

    @Test
    void aBusyMachineDoesntSpendTheCasesDailyAllowance() {
        UploadConcurrency.Permit a = concurrency.enter();
        UploadConcurrency.Permit b = concurrency.enter();
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

        assertRefused(() -> service.prepare(command(body)), ErrorCode.INDISPONIVEL);

        verify(limits, never()).perCase(any());
        a.close();
        b.close();
    }

    @Test
    void aThrottledCaseGivesThePermitBack() {
        doThrow(new RateLimitExceededException(ErrorCode.LIMITE_TENTATIVAS, 600)).when(limits).perCase(linkCase.id());

        assertRefused(() -> service.prepare(command(FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8)))),
                ErrorCode.LIMITE_TENTATIVAS);

        assertThat(concurrency.available()).isEqualTo(2);
    }

    @Test
    void aFileIdenticalToOneRefusedAsNotAllowedIsAFormatError() throws Exception {
        byte[] pdf = TestPdfs.plain();
        when(documents.findByCaseIdAndTipoAndSha256OriginalIn(any(), eq(TIPO), any()))
                .thenReturn(List.of(held(sha256(pdf), EstadoDocumento.RECUSADO, MotivoRecusa.CONTEUDO_NAO_PERMITIDO)));

        assertFormatRefused(FakeParts.of(TIPO.name(), pdf));
        verify(reservations, never()).reserve(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void aFileThatCouldntBeVerifiedIsNewAgain() throws Exception {
        byte[] pdf = TestPdfs.plain();
        when(documents.findByCaseIdAndTipoAndSha256OriginalIn(any(), eq(TIPO), any()))
                .thenReturn(List.of(held(sha256(pdf), EstadoDocumento.RECUSADO, MotivoRecusa.VERIFICACAO_FALHOU)));

        try (PreparedUpload prepared = service.prepare(command(FakeParts.of(TIPO.name(), pdf)))) {
            assertThat(prepared.files()).hasSize(1);
        }
    }

    private Document held(String sha256Original, EstadoDocumento estado, MotivoRecusa motivo) {
        return new Document(UUID.randomUUID(), workspace.id(), linkCase.id(), null, TIPO, FormatoEnvio.pdf, 1, null,
                "application/pdf", 10, null, sha256Original, null, Instant.now(), estado, motivo, null, UUID.randomUUID(),
                estado == EstadoDocumento.RECUSADO ? null : true, 1, null, null, null);
    }

    // --- parts and format ----------------------------------------------------------------------

    private void assertFormatRefused(FakeParts body) {
        assertRefused(() -> service.prepare(command(body)), ErrorCode.FORMATO_INVALIDO);
        assertThat(body.allDeleted()).as("every part deleted").isTrue();
        verify(reservations, never()).reserve(any(), any(), any(), anyLong(), anyInt());
        assertThat(concurrency.available()).isEqualTo(2);
    }

    @Test
    void aMissingTipoIsAFormatError() {
        assertFormatRefused(FakeParts.of(null, TestImages.jpeg(8, 8)));
    }

    @Test
    void anUnknownTipoIsAFormatError() {
        assertFormatRefused(FakeParts.of("PASSAPORTE", TestImages.jpeg(8, 8)));
        assertFormatRefused(FakeParts.of("responsavel", TestImages.jpeg(8, 8)));
    }

    @Test
    void twoTipoFieldsAreAFormatError() {
        assertFormatRefused(FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8)).text("tipo", TIPO.name()));
    }

    @Test
    void noFilesIsAFormatError() {
        assertFormatRefused(FakeParts.of(TIPO.name()));
        assertThat(count("formato")).isEqualTo(1);
    }

    @Test
    void aTipoTheCaseDoesntAskForIsAFormatError() {
        assertFormatRefused(FakeParts.of(DocumentoTipo.PROCURACAO.name(), TestPdfs.plain()));
    }

    @Test
    void aFileThatIsntAPdfJpegOrPngIsAFormatError() {
        assertFormatRefused(FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8), TestFiles.eicar()));
        assertFormatRefused(FakeParts.of(TIPO.name(), new byte[0]));
    }

    @Test
    void aFormatRuleViolationIsAFormatError() {
        assertFormatRefused(FakeParts.of(TIPO.name(), TestPdfs.plain(), TestImages.jpeg(8, 8)));
        assertFormatRefused(FakeParts.of(TIPO.name(), TestPdfs.plain(), TestPdfs.withLaunch()));
    }

    @Test
    void photosAddedToATypeHoldingAPdfAreAFormatError() {
        allow(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .documentos(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.pdf, 1, 100))).build().linkCase(), TIPO);

        assertFormatRefused(FakeParts.of(TIPO.name(), TestImages.png(8, 8)));
    }

    @Test
    void aBodyThatFailsToParseReleasesThePermit() {
        FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));
        body.failure = new IllegalStateException("container refused the body");

        assertThatThrownBy(() -> service.prepare(command(body))).isSameAs(body.failure);

        assertThat(concurrency.available()).isEqualTo(2);
    }

    // --- duplicates and the reservation --------------------------------------------------------

    @Test
    void acceptedPhotosAreReservedByTheirOriginalBytesAndHashed() throws Exception {
        byte[] first = TestImages.jpeg(16, 16);
        byte[] second = TestImages.png(16, 16);
        FakeParts body = FakeParts.of(TIPO.name(), first, second);

        try (PreparedUpload prepared = service.prepare(command(body))) {
            assertThat(prepared.tipo()).isEqualTo(TIPO);
            assertThat(prepared.formato()).isEqualTo(FormatoEnvio.fotos);
            assertThat(prepared.files()).extracting(PreparedUpload.IncomingFile::sha256Original)
                    .containsExactly(sha256(first), sha256(second));
            assertThat(prepared.files()).extracting(PreparedUpload.IncomingFile::kind)
                    .containsExactly(MagicBytes.Kind.JPEG, MagicBytes.Kind.PNG);
            assertThat(prepared.bytes()).isEqualTo(first.length + second.length);
            assertThat(concurrency.available()).isEqualTo(1);
            verify(reservations).reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, first.length + second.length, 2);
        }

        assertThat(body.allDeleted()).isTrue();
        assertThat(concurrency.available()).isEqualTo(2);
        verify(reservations).release(linkCase.id(), TIPO, first.length + second.length, 2);
    }

    @Test
    void aCommittedUploadKeepsItsReservation() {
        FakeParts body = FakeParts.of(TIPO.name(), TestPdfs.plain());

        try (PreparedUpload prepared = service.prepare(command(body))) {
            assertThat(prepared.formato()).isEqualTo(FormatoEnvio.pdf);
            prepared.committed();
        }

        verify(reservations, never()).release(any(), any(), anyLong(), anyInt());
        assertThat(body.allDeleted()).isTrue();
    }

    @Test
    void aPhotoAlreadyStoredForTheTypeIsSkippedAndTheCurrentCountAnswered() throws Exception {
        byte[] photo = TestImages.jpeg(16, 16);
        allow(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .documentos(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.fotos, 3, 900))).build().linkCase(), TIPO);
        when(documents.findByCaseIdAndTipoAndSha256OriginalIn(any(), eq(TIPO), any()))
                .thenReturn(List.of(held(sha256(photo), EstadoDocumento.ACEITO, null)));

        try (PreparedUpload prepared = service.prepare(command(FakeParts.of(TIPO.name(), photo)))) {
            assertThat(prepared.files()).isEmpty();
            assertThat(prepared.documentosEnviados()).isEqualTo(3);
        }

        verify(reservations, never()).reserve(any(), any(), any(), anyLong(), anyInt());
        assertThat(count("duplicado")).isEqualTo(1);
    }

    @Test
    void theSamePhotoTwiceInOneRequestCountsOnce() {
        byte[] photo = TestImages.jpeg(16, 16);

        try (PreparedUpload prepared = service.prepare(command(FakeParts.of(TIPO.name(), photo, photo)))) {
            assertThat(prepared.files()).hasSize(1);
            verify(reservations).reserve(linkCase.id(), TIPO, FormatoEnvio.fotos, photo.length, 1);
        }
    }

    @Test
    void aRetriedPdfIsNotAFormatErrorButAnswersTheCurrentCount() throws Exception {
        byte[] pdf = TestPdfs.plain();
        allow(TestCases.builder(workspace).estado(EstadoCaso.pending)
                .documentos(Map.of(TIPO, new DocumentosTipo(FormatoEnvio.pdf, 1, pdf.length))).build().linkCase(), TIPO);
        when(documents.findByCaseIdAndTipoAndSha256OriginalIn(any(), eq(TIPO), any()))
                .thenReturn(List.of(held(sha256(pdf), EstadoDocumento.VERIFICANDO, null)));

        try (PreparedUpload prepared = service.prepare(command(FakeParts.of(TIPO.name(), pdf)))) {
            assertThat(prepared.files()).isEmpty();
            assertThat(prepared.documentosEnviados()).isEqualTo(1);
        }
    }

    @Test
    void aReservationRefusalMapsToItsStatusAndCleansUp() {
        Map<Result, ErrorCode> refusals = Map.of(
                Result.ARQUIVO_GRANDE, ErrorCode.ARQUIVO_GRANDE,
                Result.LIMITE_ARQUIVOS, ErrorCode.LIMITE_ARQUIVOS,
                Result.FORMATO, ErrorCode.FORMATO_INVALIDO,
                Result.CASO_NAO_ENCONTRADO, ErrorCode.CASO_NAO_ENCONTRADO);
        refusals.forEach((result, code) -> {
            when(reservations.reserve(any(), any(), any(), anyLong(), anyInt())).thenReturn(result);
            FakeParts body = FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8));

            assertRefused(() -> service.prepare(command(body)), code);

            assertThat(body.allDeleted()).isTrue();
            assertThat(concurrency.available()).isEqualTo(2);
        });
        verify(reservations, never()).release(any(), any(), anyLong(), anyInt());
        assertThat(count("grande")).isEqualTo(1);
        assertThat(count("limite_arquivos")).isEqualTo(1);
    }

    @Test
    void theChecksRunInOrderAndTheIpLimitTakesTheSlugOnly() {
        try (PreparedUpload prepared = service.prepare(command(FakeParts.of(TIPO.name(), TestImages.jpeg(8, 8))))) {
            var order = org.mockito.Mockito.inOrder(limits, links, resolver, reservations);
            order.verify(limits).request(IP, workspace.slug());
            order.verify(links).resolveActive(workspace.slug());
            order.verify(resolver).resolve(workspace, token);
            order.verify(limits).perCase(linkCase.id());
            order.verify(reservations).reserve(any(), any(), any(), anyLong(), anyInt());
        }
        verify(documents).findByCaseIdAndTipoAndSha256OriginalIn(eq(linkCase.id()), eq(TIPO), any());
    }
}

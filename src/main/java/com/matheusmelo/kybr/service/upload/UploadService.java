package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.document.Document;
import com.matheusmelo.kybr.model.document.DocumentItemState;
import com.matheusmelo.kybr.model.document.EstadoDocumento;
import com.matheusmelo.kybr.model.document.FormatoEnvio;
import com.matheusmelo.kybr.model.document.MotivoRecusa;
import com.matheusmelo.kybr.model.document.SituacaoEnvio;
import com.matheusmelo.kybr.model.link.DocumentosTipo;
import com.matheusmelo.kybr.model.link.EstadoCaso;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.DocumentRepository;
import com.matheusmelo.kybr.repository.UploadReservations;
import com.matheusmelo.kybr.service.link.LinkService;
import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import com.matheusmelo.kybr.service.upload.scan.ScanWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The seller document upload, from the cheap checks to the reservation. Each step is final, and
 * the body isn't parsed until every check that needs no body has passed:
 *
 * <ol>
 *   <li>the per-IP limit (on the slug as sent, before any lookup);
 *   <li>the link (unknown or disabled: 404 {@code LINK_NAO_ENCONTRADO});
 *   <li>the token's capability (404 {@code CASO_NAO_ENCONTRADO});
 *   <li>a decided case (409 {@code CASO_DECIDIDO});
 *   <li>a concurrency permit (503 {@code INDISPONIVEL}, {@code Retry-After: 5});
 *   <li>the per-case limit, taken only once the permit is held, so a busy server's 503 doesn't spend
 *       the case's daily allowance;
 *   <li>the parts: exactly one known {@code tipo} the capability allows, at least one file, every
 *       file a PDF, JPEG or PNG by its first bytes (415 {@code FORMATO_INVALIDO});
 *   <li>photos: the header pre-check (size caps, encodings the sanitizer can't read; 415);
 *   <li>duplicates (by the SHA-256 of the bytes as sent) dropped, within the request and against
 *       the type's files that still count (accepted or being verified), so a retry never adds a
 *       page; a file identical to one refused as not allowed is 415 at once; then the format rules
 *       on what is left (415);
 *   <li>the reservation, which counts files still being verified (413 {@code ARQUIVO_GRANDE} /
 *       {@code LIMITE_ARQUIVOS}, 415, 404).
 * </ol>
 *
 * Duplicates are dropped before the format rules so that a retried PDF answers its count instead of
 * "a PDF added to a type that holds one". File names and declared content types are never read.
 */
@Service
public class UploadService {

    public static final String METRIC = "kybr.upload";
    static final String TIPO_FIELD = "tipo";
    static final String FILES_FIELD = "arquivos";
    /** Longer than any document type name; a longer {@code tipo} is unknown without being read whole. */
    private static final int MAX_TIPO_BYTES = 64;

    private static final Map<ErrorCode, String> RESULTADOS = Map.of(
            ErrorCode.LIMITE_TENTATIVAS, "limitado",
            ErrorCode.LINK_NAO_ENCONTRADO, "nao_encontrado",
            ErrorCode.CASO_NAO_ENCONTRADO, "nao_encontrado",
            ErrorCode.CASO_DECIDIDO, "decidido",
            ErrorCode.INDISPONIVEL, "indisponivel",
            ErrorCode.FORMATO_INVALIDO, "formato",
            ErrorCode.ARQUIVO_GRANDE, "grande",
            ErrorCode.LIMITE_ARQUIVOS, "limite_arquivos");

    private final UploadRateLimits limits;
    private final LinkService links;
    private final UploadCapabilityResolver resolver;
    private final DocumentRepository documents;
    private final UploadReservations reservations;
    private final UploadConcurrency concurrency;
    private final UploadCommit commit;
    private final ImageHeaderCheck headerCheck;
    private final MeterRegistry meters;

    @Autowired
    public UploadService(UploadRateLimits limits, LinkService links, UploadCapabilityResolver resolver,
            DocumentRepository documents, UploadReservations reservations, UploadConcurrency concurrency,
            UploadCommit commit, KybrProperties properties, MeterRegistry meters) {
        this(limits, links, resolver, documents, reservations, concurrency, commit,
                new ImageHeaderCheck(properties.image().maxPixels(), properties.image().maxSide()), meters);
    }

    UploadService(UploadRateLimits limits, LinkService links, UploadCapabilityResolver resolver,
            DocumentRepository documents, UploadReservations reservations, UploadConcurrency concurrency,
            UploadCommit commit, ImageHeaderCheck headerCheck, MeterRegistry meters) {
        this.limits = limits;
        this.links = links;
        this.resolver = resolver;
        this.documents = documents;
        this.reservations = reservations;
        this.concurrency = concurrency;
        this.commit = commit;
        this.headerCheck = headerCheck;
        this.meters = meters;
    }

    /**
     * The whole upload: the checks and the reservation ({@link #prepare}), then the quarantine and
     * records ({@link UploadCommit}); the files are verified later by the scan job. Whatever the
     * exit, every part's temporary file is deleted, the concurrency permit released, and the
     * reservation given back unless committed. A request whose files the type already holds (a
     * retry) answers the type's current state.
     *
     * @throws ApiException with the refusal's code; nothing is kept
     */
    public UploadOutcome upload(UploadCommand command) {
        try (PreparedUpload prepared = prepare(command)) {
            if (prepared.files().isEmpty()) {
                DocumentItemState current = currentState(prepared);
                return new UploadOutcome(prepared.tipo(), current.enviados(), current.situacao());
            }
            try {
                DocumentItemState state = commit.commit(prepared, command.clientIp());
                count("aceito");
                return new UploadOutcome(prepared.tipo(), state.enviados(), state.situacao());
            } catch (ApiException e) {
                count(RESULTADOS.getOrDefault(e.code(), "erro"));
                throw e;
            } catch (RuntimeException | Error e) {
                count("erro");
                throw e;
            }
        }
    }

    /**
     * Runs the checks and reserves room for the new files. The caller must close the result.
     *
     * @throws ApiException with the refusal's code; nothing is left reserved, parsed or held
     */
    public PreparedUpload prepare(UploadCommand command) {
        try {
            return checkedPrepare(command);
        } catch (ApiException e) {
            count(RESULTADOS.getOrDefault(e.code(), "erro"));
            throw e;
        }
    }

    /** Counts one upload outcome on {@value #METRIC}. */
    void count(String resultado) {
        meters.counter(METRIC, "resultado", resultado).increment();
    }

    private PreparedUpload checkedPrepare(UploadCommand command) {
        limits.request(command.clientIp(), command.slug());
        Workspace workspace = links.resolveActive(command.slug());
        UploadCapability capability = resolver.resolve(workspace, command.token());
        EstadoCaso estado = capability.linkCase().estado();
        if (estado == EstadoCaso.approved || estado == EstadoCaso.blocked) {
            throw new ApiException(ErrorCode.CASO_DECIDIDO);
        }
        UploadConcurrency.Permit permit = concurrency.enter();
        List<UploadPart> parts = List.of();
        try {
            limits.perCase(capability.caseId());
            parts = command.parts().parse();
            PreparedUpload prepared = reserve(capability, parts, permit);
            if (prepared.files().isEmpty()) {
                count("duplicado");
            }
            return prepared;
        } catch (RuntimeException | Error e) {
            parts.forEach(UploadPart::delete);
            permit.close();
            throw e;
        }
    }

    private PreparedUpload reserve(UploadCapability capability, List<UploadPart> parts, UploadConcurrency.Permit permit) {
        DocumentoTipo tipo = tipo(parts, capability);
        List<UploadPart> fileParts = parts.stream().filter(part -> FILES_FIELD.equals(part.name())).toList();
        if (fileParts.isEmpty()) {
            throw formatError();
        }
        List<PreparedUpload.IncomingFile> typed = new ArrayList<>();
        for (UploadPart part : fileParts) {
            typed.add(typeAndHash(part));
        }
        for (PreparedUpload.IncomingFile file : typed) {
            if (file.kind().isPhoto()) {
                checkHeader(file);
            }
        }
        List<PreparedUpload.IncomingFile> fresh = dropDuplicates(capability, tipo, typed);
        DocumentosTipo held = capability.linkCase().documentos().get(tipo);
        int enviados = held == null ? 0 : held.arquivos();
        if (fresh.isEmpty()) {
            return new PreparedUpload(capability, tipo, held == null ? null : held.formato(), fresh, enviados, parts,
                    permit, reservations);
        }
        FormatoEnvio formato = FormatRules.formatFor(held == null ? null : held.formato(),
                fresh.stream().map(PreparedUpload.IncomingFile::kind).toList()).orElseThrow(UploadService::formatError);
        long bytes = fresh.stream().mapToLong(PreparedUpload.IncomingFile::size).sum();
        switch (reservations.reserve(capability.caseId(), tipo, formato, bytes, fresh.size())) {
            case RESERVED -> {
                return new PreparedUpload(capability, tipo, formato, fresh, enviados, parts, permit, reservations);
            }
            case ARQUIVO_GRANDE -> throw new ApiException(ErrorCode.ARQUIVO_GRANDE);
            case LIMITE_ARQUIVOS -> throw new ApiException(ErrorCode.LIMITE_ARQUIVOS);
            case FORMATO -> throw formatError();
            case CASO_NAO_ENCONTRADO -> throw new ApiException(ErrorCode.CASO_NAO_ENCONTRADO);
            default -> throw new IllegalStateException("unexpected reservation result");
        }
    }

    private DocumentoTipo tipo(List<UploadPart> parts, UploadCapability capability) {
        List<UploadPart> tipoParts = parts.stream().filter(part -> TIPO_FIELD.equals(part.name())).toList();
        if (tipoParts.size() != 1) {
            throw formatError();
        }
        String value = readText(tipoParts.getFirst());
        DocumentoTipo tipo = parseTipo(value).orElseThrow(UploadService::formatError);
        if (!capability.tiposPermitidos().contains(tipo)) {
            throw formatError();
        }
        return tipo;
    }

    private static Optional<DocumentoTipo> parseTipo(String value) {
        for (DocumentoTipo tipo : DocumentoTipo.values()) {
            if (tipo.name().equals(value)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }

    private static String readText(UploadPart part) {
        if (part.size() > MAX_TIPO_BYTES) {
            return "";
        }
        try (InputStream in = part.open()) {
            return new String(in.readNBytes(MAX_TIPO_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't read an upload field", e);
        }
    }

    private static PreparedUpload.IncomingFile typeAndHash(UploadPart part) {
        MessageDigest digest = sha256();
        try (DigestInputStream in = new DigestInputStream(part.open(), digest)) {
            MagicBytes.Kind kind = MagicBytes.detect(in).orElseThrow(UploadService::formatError);
            in.transferTo(OutputStream.nullOutputStream()); // the rest is read only to finish the hash
            return new PreparedUpload.IncomingFile(part, kind, HexFormat.of().formatHex(digest.digest()),
                    part.size());
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't read an uploaded file", e);
        }
    }

    /**
     * The new files: a repeat within the request counts once, a file the type already holds (accepted
     * or being verified) is skipped, and a file identical to one refused as not allowed is refused at
     * once. A file refused only because it couldn't be verified may be sent again.
     */
    private List<PreparedUpload.IncomingFile> dropDuplicates(UploadCapability capability, DocumentoTipo tipo,
            List<PreparedUpload.IncomingFile> typed) {
        Map<String, List<Document>> held = documents.findByCaseIdAndTipoAndSha256OriginalIn(capability.caseId(), tipo,
                        typed.stream().map(PreparedUpload.IncomingFile::sha256Original).distinct().toList())
                .stream().collect(Collectors.groupingBy(Document::sha256Original));
        Set<String> seen = new HashSet<>();
        List<PreparedUpload.IncomingFile> fresh = new ArrayList<>();
        for (PreparedUpload.IncomingFile file : typed) {
            List<Document> same = held.getOrDefault(file.sha256Original(), List.of());
            if (same.stream().anyMatch(d -> d.estadoOuAceito() != EstadoDocumento.RECUSADO)) {
                continue;
            }
            if (same.stream().anyMatch(d -> d.motivoRecusa() == MotivoRecusa.CONTEUDO_NAO_PERMITIDO)) {
                throw formatError();
            }
            if (seen.add(file.sha256Original())) {
                fresh.add(file);
            }
        }
        return fresh;
    }

    /** The photo header pre-check, before anything is reserved: 415 for what can't be processed. */
    private void checkHeader(PreparedUpload.IncomingFile file) {
        ImageHeaderCheck.Result header;
        try (InputStream content = file.part().open()) {
            header = headerCheck.check(content);
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't read an uploaded file", e);
        }
        switch (header) {
            case ImageHeaderCheck.Result.Ok ok -> {
            }
            case ImageHeaderCheck.Result.Unsupported unsupported -> {
                meters.counter(ScanWorker.REENCODE_METRIC, "resultado", "unsupported").increment();
                throw formatError();
            }
            case ImageHeaderCheck.Result.Rejected rejected -> {
                meters.counter(ScanWorker.REENCODE_METRIC, "resultado", "rejected").increment();
                throw formatError();
            }
        }
    }

    private DocumentItemState currentState(PreparedUpload prepared) {
        return DocumentItemState.of(documents.findByCaseIdAndTipoOrderByPaginaAsc(prepared.capability().caseId(),
                prepared.tipo())).orElseGet(() -> new DocumentItemState(SituacaoEnvio.aceito,
                prepared.documentosEnviados(), 0, null, prepared.formato()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ApiException formatError() {
        return new ApiException(ErrorCode.FORMATO_INVALIDO);
    }
}

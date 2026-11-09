package com.matheusmelo.kybr.controller;

import static com.matheusmelo.kybr.controller.LinkApiDocs.BAD_REQUEST;
import static com.matheusmelo.kybr.controller.LinkApiDocs.INTERNAL;
import static com.matheusmelo.kybr.controller.LinkApiDocs.NOT_ACCEPTABLE;
import static com.matheusmelo.kybr.controller.LinkApiDocs.SLUG;
import static com.matheusmelo.kybr.controller.LinkApiDocs.SLUG_PATTERN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.TOKEN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.TOKEN_PATTERN;

import com.matheusmelo.kybr.dto.link.ResultadoPublico;
import com.matheusmelo.kybr.dto.link.UploadForm;
import com.matheusmelo.kybr.dto.link.UploadResponse;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.service.upload.UploadCommand;
import com.matheusmelo.kybr.service.upload.UploadOutcome;
import com.matheusmelo.kybr.service.upload.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * E06, the seller document upload, called from the browser: no BFF key, no credentials, the case
 * token in the path as the only capability. Thin: the multipart body is handed over unparsed, and
 * {@link UploadService} runs every check (rate limits, link, token, decided case, concurrency)
 * before it asks for the parts. The original file names are never read.
 */
@RestController
@Tag(name = "Seller documents", description = "The seller's document upload, called from the browser.")
public class UploadController {

    static final String DESCRIPTION = """
            Called from the seller's browser, not the link's server: no `X-Kybr-BFF-Key`, no \
            `X-Kybr-Client-IP` and no credentials (send it with `credentials: "omit"`). The case token in the \
            path is the only capability. CORS is answered for the product's origin (`https://kybr.com.br`; \
            staging also allows its configured preview origins), exposing `Retry-After` and `X-Request-Id`, \
            never with credentials. `Origin` is not a control: a request from any other origin, or with none, \
            is processed the same way, and only the browser is kept from reading the answer.

            The body is `multipart/form-data`: one `tipo` and one or more `arquivos`. Files are typed by their \
            first bytes only (`%PDF-`, `FF D8 FF`, the PNG signature); the declared content type and the file \
            name are ignored, and the file name is never stored or echoed. A request sends exactly one PDF, or \
            one or more JPEG/PNG photos (pages of one document). A PDF can't be added to a type that already \
            has files, and photos can't be added to a type that holds a PDF. A type holds at most 10,485,760 \
            bytes of files as sent, across calls, and a case at most 30 files across types. A file already \
            received for the type and not refused (the same bytes) is ignored, so a retry after a lost answer \
            adds nothing and answers the type's current state; files still being checked count toward both \
            limits. A file identical to one refused as not allowed is refused at once (415).

            The answer is 202: files are received into a private quarantine and checked in the background. \
            Every file is scanned; photos are then re-encoded without metadata (EXIF and GPS included) and \
            downscaled to a 4,000 px long edge. PDFs are stored as sent. The case read (D4) shows each document \
            item's `situacao_envio` (`verificando`, then `aceito` or `recusado` with `motivo_recusa`: \
            `CONTEUDO_NAO_PERMITIDO`, `ARQUIVO_INVALIDO` or `VERIFICACAO_FALHOU`); poll it \
            while any item is `verificando`. CMYK, YCCK, 12-bit and arithmetic-coded JPEGs, and photos over 50 \
            megapixels or 12,000 px a side, are refused at once (415): send these as PDF.

            Checks run in this order: client limits, link, token, decided case, capacity, case limit, then the \
            body. A refused request's body is never parsed.""";

    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping(value = "/public/v/{slug}/cases/{token}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Upload documents", description = DESCRIPTION,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(implementation = UploadForm.class))))
    @ApiResponse(responseCode = "202", description = "The files were received (now or by an earlier call) and are "
            + "being checked: the type, how many of its files count, and `situacao_envio`. Poll the case read (D4) "
            + "until the type's item is no longer `verificando`.")
    @ApiResponse(responseCode = "400", description = BAD_REQUEST)
    @ApiResponse(responseCode = "404", description = "- `LINK_NAO_ENCONTRADO`: the slug is unknown or malformed, the "
            + "link isn't set up yet, or it is disabled. Decided from the slug, before the token or the body is read.\n"
            + "- `CASO_NAO_ENCONTRADO`: the token is unknown, belongs to another link, or no longer opens the case "
            + "(its documents were deleted); the same response either way.")
    @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE)
    @ApiResponse(responseCode = "409", description = "- `CASO_DECIDIDO`: the case was approved or blocked; it takes "
            + "no more documents.")
    @ApiResponse(responseCode = "413", description = "- `ARQUIVO_GRANDE`: the type would hold more than 10,485,760 "
            + "bytes of files as sent, across calls.\n"
            + "- `LIMITE_ARQUIVOS`: the case would hold more than 30 files.\n"
            + "- `REQUISICAO_GRANDE`: the body is over 10,747,904 bytes (10 MiB of files plus 256 KiB of multipart "
            + "overhead), has more than 32 parts, a part header over 8 KiB, or a file over 10 MiB.")
    @ApiResponse(responseCode = "415", description = "- `FORMATO_INVALIDO`: `tipo` is missing, unknown or not asked "
            + "for on this case; no file; a file that isn't a PDF, JPEG or PNG by its first bytes; more than one PDF, "
            + "or a PDF with photos; a PDF added to a type that has files, or photos added to a type that holds a "
            + "PDF; a photo that can't be processed (see above); or a file identical to one refused as not allowed.\n"
            + "- `MIDIA_NAO_SUPORTADA`: the body isn't `multipart/form-data`.")
    @ApiResponse(responseCode = "429", description = "- `LIMITE_TENTATIVAS`: too many uploads from this address "
            + "(120 per 10 minutes) or for this case (60 per day).")
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = "- `INDISPONIVEL`: storage is unavailable or this server is busy; "
            + "nothing was kept. Send the same files "
            + "again after `Retry-After` seconds.")
    public UploadResponse upload(
            @Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN)) @PathVariable String slug,
            @Parameter(description = TOKEN, schema = @Schema(pattern = TOKEN_PATTERN)) @PathVariable String token,
            HttpServletRequest request) {
        UploadOutcome outcome = uploads.upload(new UploadCommand(slug, token,
                (ClientIp) request.getAttribute(ClientIp.ATTRIBUTE), new ServletUploadParts(request)));
        return new UploadResponse(ResultadoPublico.DocumentoTipo.valueOf(outcome.tipo().name()),
                outcome.documentosEnviados(), ResultadoPublico.SituacaoEnvio.valueOf(outcome.situacao().name()));
    }
}

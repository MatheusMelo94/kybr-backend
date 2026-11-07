package com.matheusmelo.kybr.controller;

import com.matheusmelo.kybr.config.filter.IdempotencyInterceptor;
import com.matheusmelo.kybr.config.filter.LinkSlugInterceptor;
import com.matheusmelo.kybr.dto.link.CaseResponse;
import com.matheusmelo.kybr.dto.link.ContactRequest;
import com.matheusmelo.kybr.dto.link.LinkInfoResponse;
import com.matheusmelo.kybr.dto.link.LookupResponse;
import com.matheusmelo.kybr.dto.link.VerifyRequest;
import com.matheusmelo.kybr.dto.link.VerifyResponse;
import com.matheusmelo.kybr.mapper.LinkInfoMapper;
import com.matheusmelo.kybr.mapper.LookupMapper;
import com.matheusmelo.kybr.mapper.PublicResultMapper;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.idempotency.IdempotencyAttempt;
import com.matheusmelo.kybr.service.idempotency.IdempotencyCompletion;
import com.matheusmelo.kybr.service.idempotency.IdempotencyProfile;
import com.matheusmelo.kybr.service.idempotency.IdempotencyService;
import com.matheusmelo.kybr.service.idempotency.Idempotent;
import com.matheusmelo.kybr.service.idempotency.SlugBindingResolver;
import com.matheusmelo.kybr.service.link.CaseReadService;
import com.matheusmelo.kybr.service.link.ContactService;
import com.matheusmelo.kybr.service.link.LinkLookupService;
import com.matheusmelo.kybr.service.link.LinkRateLimits;
import com.matheusmelo.kybr.service.link.LinkRoute;
import com.matheusmelo.kybr.service.link.LinkService;
import com.matheusmelo.kybr.service.link.StartCommand;
import com.matheusmelo.kybr.service.link.VerificationStartService;
import static com.matheusmelo.kybr.controller.LinkApiDocs.BAD_BODY;
import static com.matheusmelo.kybr.controller.LinkApiDocs.BAD_REQUEST;
import static com.matheusmelo.kybr.controller.LinkApiDocs.BFF_KEY;
import static com.matheusmelo.kybr.controller.LinkApiDocs.CASE_NOT_FOUND;
import static com.matheusmelo.kybr.controller.LinkApiDocs.CLIENT_IP;
import static com.matheusmelo.kybr.controller.LinkApiDocs.FORBIDDEN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.INTERNAL;
import static com.matheusmelo.kybr.controller.LinkApiDocs.LINK_NOT_FOUND;
import static com.matheusmelo.kybr.controller.LinkApiDocs.LINK_NOT_FOUND_READ;
import static com.matheusmelo.kybr.controller.LinkApiDocs.NOT_ACCEPTABLE;
import static com.matheusmelo.kybr.controller.LinkApiDocs.SLUG;
import static com.matheusmelo.kybr.controller.LinkApiDocs.SLUG_PATTERN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.TOKEN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.TOKEN_PATTERN;
import static com.matheusmelo.kybr.controller.LinkApiDocs.TOO_LARGE;
import static com.matheusmelo.kybr.controller.LinkApiDocs.UNAVAILABLE;
import static com.matheusmelo.kybr.controller.LinkApiDocs.UNSUPPORTED;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller link, under the unversioned base path {@code /public/v/{slug}}. Thin: the workspace
 * is resolved from the slug by {@link LinkSlugInterceptor} before binding; services decide.
 */
@RestController
@Tag(name = "Seller link", description = "The public seller link: link info, company lookup, verification start, "
        + "case read and the seller's contact e-mail. Called server to server by the link's frontend.")
public class LinkController {

    private final LinkService links;
    private final LinkInfoMapper linkInfoMapper;
    private final LinkLookupService lookups;
    private final LookupMapper lookupMapper;
    private final VerificationStartService verifications;
    private final CaseReadService caseReads;
    private final PublicResultMapper publicResultMapper;
    private final ContactService contacts;

    public LinkController(LinkService links, LinkInfoMapper linkInfoMapper, LinkLookupService lookups,
            LookupMapper lookupMapper, VerificationStartService verifications, CaseReadService caseReads,
            PublicResultMapper publicResultMapper, ContactService contacts) {
        this.links = links;
        this.linkInfoMapper = linkInfoMapper;
        this.lookups = lookups;
        this.lookupMapper = lookupMapper;
        this.verifications = verifications;
        this.caseReads = caseReads;
        this.publicResultMapper = publicResultMapper;
        this.contacts = contacts;
    }

    @LinkRoute
    @GetMapping(value = "/public/v/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Link info", description = "The workspace's name and how the seller reaches it. Not "
            + "cached on the server, so a change is visible on the next call. The frontend caches it per slug and "
            + "sends no client IP.", parameters = @Parameter(ref = BFF_KEY))
    @ApiResponse(responseCode = "200", description = "The link's info.")
    @ApiResponse(responseCode = "400", description = BAD_REQUEST)
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = LINK_NOT_FOUND)
    @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE)
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = UNAVAILABLE)
    public LinkInfoResponse info(@Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN))
            @PathVariable String slug,
            @Parameter(hidden = true) @RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace) {
        return linkInfoMapper.toResponse(links.requirePublishableContact(workspace));
    }

    @LinkRoute
    @GetMapping(value = "/public/v/{slug}/lookup", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Look up a company", description = "Fills in the company for the seller to confirm: its "
            + "name (any CPF masked), town, state and main activity. Answered from a local cache first. Checks run "
            + "in this order: slug, autofill switches, CNPJ, client limits, then the lookup. A failed lookup never "
            + "blocks starting the verification.",
            parameters = {@Parameter(ref = BFF_KEY), @Parameter(ref = CLIENT_IP)})
    @ApiResponse(responseCode = "200", description = "The company.")
    @ApiResponse(responseCode = "400", description = "- `CNPJ_INVALIDO` (`field: cnpj`): missing, repeated, not "
            + "canonical (lower case, punctuation), 14 identical characters, or a wrong check digit. 400 here, "
            + "where the CNPJ is a query parameter; 422 on verify.\n"
            + "- `REQUISICAO_INVALIDA`: a path that isn't normalised.")
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = LINK_NOT_FOUND + "\n"
            + "- `EMPRESA_NAO_ENCONTRADA`: a valid CNPJ with no company data.")
    @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE)
    @ApiResponse(responseCode = "429", description = "- `LIMITE_CONSULTAS`: too many lookups from this client "
            + "(per minute or per day).")
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = "- `AUTOFILL_DESATIVADO`: lookups are switched off (for this "
            + "link or for every link), or the company isn't cached and this link may only read the cache right "
            + "now. Checked before the CNPJ.\n"
            + "- `INDISPONIVEL`: the data source failed, timed out or returned an incomplete record, or the "
            + "database is unavailable.")
    public LookupResponse lookup(@Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN))
            @PathVariable String slug,
            @Parameter(description = "The company's CNPJ, canonical: 14 upper-case characters, no punctuation, "
                    + "valid check digits; numeric or alphanumeric. The only query parameter.", required = true,
                    schema = @Schema(pattern = "^[0-9A-Z]{12}[0-9]{2}$", example = "12ABC34501DE35"))
            @RequestParam(name = "cnpj", required = false) String cnpj,
            @Parameter(hidden = true) @RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace,
            HttpServletRequest servletRequest) {
        return lookupMapper.toResponse(lookups.lookup(workspace, clientIp(servletRequest), cnpj));
    }

    @LinkRoute
    @PostMapping(value = "/public/v/{slug}/verify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(profile = IdempotencyProfile.PUBLIC_VERIFY, binding = SlugBindingResolver.class,
            completion = IdempotencyCompletion.TRANSACTIONAL, outcomeMetric = LinkRateLimits.VERIFY_METRIC)
    @Operation(summary = "Start a verification", description = "Creates a case for the company and starts "
            + "verifying it; read the result with the returned token. A valid CNPJ the data source doesn't know is "
            + "still accepted and goes to human review. `ref` and `ext_id` are stored untouched and never echoed. "
            + "When several fields are invalid, the first in `cnpj`, `responsavel`, `ref`, `ext_id` order is "
            + "reported.", parameters = {@Parameter(ref = BFF_KEY), @Parameter(ref = CLIENT_IP)})
    @ApiResponse(responseCode = "201", description = "The case was created.")
    @ApiResponse(responseCode = "400", description = BAD_BODY)
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = LINK_NOT_FOUND)
    @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE)
    @ApiResponse(responseCode = "413", description = TOO_LARGE)
    @ApiResponse(responseCode = "415", description = UNSUPPORTED)
    @ApiResponse(responseCode = "422", description = "- `CNPJ_INVALIDO` (`field: cnpj`): missing, not a string, "
            + "not canonical, or a wrong check digit. 422 here; 400 on the lookup, where the CNPJ is a query "
            + "parameter.\n"
            + "- `RESPONSAVEL_INVALIDO` (`field: responsavel`): missing, not a string, fewer than two words, over "
            + "120 characters, or characters other than letters, space, `'`, `-` and `.`.\n"
            + "- `VALOR_INVALIDO`: `ref` or `ext_id` over 256 characters or not printable ASCII (`field` names it); "
            + "an unknown property; or an `Idempotency-Key` that is missing, not a UUID, the nil or max UUID, or "
            + "reused with a different body (`field: Idempotency-Key`).")
    @ApiResponse(responseCode = "429", description = "- `LIMITE_CNPJ`: the same CNPJ was submitted too often on "
            + "this link.\n"
            + "- `LIMITE_TENTATIVAS`: too many verifications from this client on this link.")
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = "- `INDISPONIVEL`: the database is unavailable, or a request "
            + "with the same `Idempotency-Key` is still running (then `Retry-After: 1`; retry with the same key).",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying with the same "
                    + "key, when the same key is still running.", schema = @Schema(type = "integer", minimum = "1")))
    public VerifyResponse verify(@Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN))
            @PathVariable String slug,
            @Parameter(description = "A UUID made fresh for each attempt and reused only to retry after an "
                    + "unknown outcome. For 10 minutes, the same key with the same body on the same slug replays "
                    + "the first response without creating another case or counting against a limit; the same "
                    + "key with a different body is 422. Any 2xx, and any 4xx other than 401, 408 and 429, is "
                    + "replayed (a 404 included); 401, 408, 429 and 5xx are never stored, so the same key runs "
                    + "again.",
                    schema = @Schema(format = "uuid"))
            @RequestHeader(IdempotencyService.KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody VerifyRequest request,
            @Parameter(hidden = true) @RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace,
            HttpServletRequest servletRequest) {
        StartCommand command = new StartCommand(request.cnpj(), request.responsavel(), request.ref(), request.extId(),
                idempotencyKey);
        IdempotencyAttempt attempt =
                (IdempotencyAttempt) servletRequest.getAttribute(IdempotencyInterceptor.ATTEMPT_ATTRIBUTE);
        return verifications.start(workspace, clientIp(servletRequest), command, attempt, VerifyResponse::new);
    }

    @LinkRoute(readableWhenDisabled = true)
    @GetMapping(value = "/public/v/{slug}/cases/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read a case", description = "The case's state and its seller-facing result. Polled while "
            + "the verification runs. Readable even when the link has been disabled.",
            parameters = {@Parameter(ref = BFF_KEY), @Parameter(ref = CLIENT_IP)})
    @ApiResponse(responseCode = "200", description = "The case.")
    @ApiResponse(responseCode = "400", description = BAD_REQUEST)
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = LINK_NOT_FOUND_READ + "\n" + CASE_NOT_FOUND)
    @ApiResponse(responseCode = "406", description = NOT_ACCEPTABLE)
    @ApiResponse(responseCode = "429", description = "- `LIMITE_TENTATIVAS`: too many reads from this client. "
            + "Checked before the token is read.")
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = UNAVAILABLE)
    public CaseResponse readCase(@Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN))
            @PathVariable String slug,
            @Parameter(description = TOKEN, schema = @Schema(pattern = TOKEN_PATTERN)) @PathVariable String token,
            @Parameter(hidden = true) @RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace,
            HttpServletRequest servletRequest) {
        return publicResultMapper.toResponse(caseReads.read(workspace, clientIp(servletRequest), token));
    }

    @LinkRoute
    @PutMapping(value = "/public/v/{slug}/cases/{token}/contact", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Save the seller's contact e-mail", description = "Stores the address for messages about "
            + "this case only; the case read then shows it masked. Sending the current address again changes "
            + "nothing. Checks run in this order: slug, client limit, case, address, decided case, then the "
            + "per-case change limit.", parameters = {@Parameter(ref = BFF_KEY), @Parameter(ref = CLIENT_IP)})
    @ApiResponse(responseCode = "204", description = "Saved (or unchanged).")
    @ApiResponse(responseCode = "400", description = BAD_BODY)
    @ApiResponse(responseCode = "403", description = FORBIDDEN)
    @ApiResponse(responseCode = "404", description = LINK_NOT_FOUND + "\n" + CASE_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "- `CASO_DECIDIDO`: the case is already approved or blocked.")
    @ApiResponse(responseCode = "413", description = TOO_LARGE)
    @ApiResponse(responseCode = "415", description = UNSUPPORTED)
    @ApiResponse(responseCode = "422", description = "- `EMAIL_INVALIDO` (`field: email`): missing, not a string, "
            + "over 254 characters, with control or format characters, or not a plain address.\n"
            + "- `VALOR_INVALIDO`: an unknown property.")
    @ApiResponse(responseCode = "429", description = "- `LIMITE_TENTATIVAS`: too many requests from this client, or "
            + "too many address changes on this case today.")
    @ApiResponse(responseCode = "500", description = INTERNAL)
    @ApiResponse(responseCode = "503", description = UNAVAILABLE)
    public void changeContact(@Parameter(description = SLUG, schema = @Schema(pattern = SLUG_PATTERN))
            @PathVariable String slug,
            @Parameter(description = TOKEN, schema = @Schema(pattern = TOKEN_PATTERN)) @PathVariable String token,
            @RequestBody ContactRequest request,
            @Parameter(hidden = true) @RequestAttribute(LinkSlugInterceptor.WORKSPACE_ATTRIBUTE) Workspace workspace,
            HttpServletRequest servletRequest) {
        contacts.change(workspace, clientIp(servletRequest), token, request.email());
    }

    private static ClientIp clientIp(HttpServletRequest request) {
        return (ClientIp) request.getAttribute(ClientIp.ATTRIBUTE);
    }
}

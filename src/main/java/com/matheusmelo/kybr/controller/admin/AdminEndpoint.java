package com.matheusmelo.kybr.controller.admin;

import com.matheusmelo.kybr.dto.admin.AdminCreatedResponse;
import com.matheusmelo.kybr.dto.admin.AdminErrorResponse;
import com.matheusmelo.kybr.dto.admin.AdminSwitchesResponse;
import com.matheusmelo.kybr.mapper.AdminWorkspaceMapper;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.service.email.OperatorEmailService;
import com.matheusmelo.kybr.service.provisioning.ProvisionedWorkspace;
import com.matheusmelo.kybr.service.provisioning.ProvisioningException;
import com.matheusmelo.kybr.service.provisioning.ProvisioningException.Reason;
import com.matheusmelo.kybr.service.provisioning.ProvisioningService;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

/**
 * The operator's admin endpoint, served on the management port only and reachable only with an
 * admin token (the management security chain authenticates; this class never checks tokens).
 *
 * <ul>
 *   <li>{@code POST /actuator/admin/workspaces} {@code {nome, categoria, contato_tipo,
 *       contato_valor, plano[, slug]}} → 201 {@code {id, slug}}.
 *   <li>{@code POST /actuator/admin/link-ativo} and {@code /autofill-ativo} {@code {slug, ativo}},
 *       {@code /autofill-global} and {@code /gasto-provedor} {@code {ativo}} → 204. A workspace's
 *       switches apply to its next request; the global ones on this machine at once and on every
 *       other machine within the runtime-flag cache (10 s).
 *   <li>{@code POST /actuator/admin/email-teste} {@code {email}} → 204 once the fixed test e-mail
 *       is handed to the provider (audited); 400 {@code INVALID} unless the address is on
 *       {@code kybr.email.test-recipients}; 503 {@code UNAVAILABLE} when it wasn't sent (capped or a
 *       provider failure), never with provider details.
 *   <li>{@code GET /actuator/admin} → the global switches; {@code GET /actuator/admin/{slug}} →
 *       one workspace.
 * </ul>
 *
 * Actuator binds a body as a map of strings and ignores unknown fields, so every value is a JSON
 * string ({@code "ativo": "false"}; a non-string value is refused by the JSON reader with 422) and
 * is checked strictly here: the provisioning service re-validates every field, and a switch takes
 * exactly {@code "true"} or {@code "false"}. Refusals are {@code {code, field}}: 400
 * {@code INVALID}, 404 {@code NOT_FOUND}, 409 {@code CONFLICT}.
 * Every write is audited by the service with the operator as actor and the token slot used.
 */
@Component
@Endpoint(id = "admin")
public class AdminEndpoint {

    static final String CREATE = "workspaces";
    static final String LINK_ATIVO = "link-ativo";
    static final String AUTOFILL_ATIVO = "autofill-ativo";
    static final String AUTOFILL_GLOBAL = "autofill-global";
    static final String GASTO_PROVEDOR = "gasto-provedor";
    static final String EMAIL_TESTE = "email-teste";
    static final String UNAVAILABLE = "UNAVAILABLE";

    private final ProvisioningService provisioning;
    private final RuntimeFlagsService flags;
    private final AdminWorkspaceMapper workspaceMapper;
    private final OperatorEmailService operatorEmails;

    public AdminEndpoint(ProvisioningService provisioning, RuntimeFlagsService flags,
            AdminWorkspaceMapper workspaceMapper, OperatorEmailService operatorEmails) {
        this.provisioning = provisioning;
        this.flags = flags;
        this.workspaceMapper = workspaceMapper;
        this.operatorEmails = operatorEmails;
    }

    @ReadOperation
    public AdminSwitchesResponse switches() {
        return new AdminSwitchesResponse(flags.autofillOn(), flags.providerSpendOn());
    }

    @ReadOperation
    public WebEndpointResponse<Object> workspace(@Selector String slug) {
        try {
            return new WebEndpointResponse<>(workspaceMapper.toResponse(provisioning.workspace(slug)),
                    WebEndpointResponse.STATUS_OK);
        } catch (ProvisioningException e) {
            return refused(e);
        }
    }

    @WriteOperation
    public WebEndpointResponse<Object> operate(@Selector String operacao, SecurityContext security,
            @Nullable String nome, @Nullable String categoria, @Nullable String contato_tipo,
            @Nullable String contato_valor, @Nullable String plano, @Nullable String slug,
            @Nullable String ativo, @Nullable String email) {
        TokenSlot slot = slot(security);
        try {
            switch (operacao) {
                case CREATE -> {
                    Map<String, Object> input = new LinkedHashMap<>();
                    putPresent(input, "nome", nome);
                    putPresent(input, "categoria", categoria);
                    putPresent(input, "contato_tipo", contato_tipo);
                    putPresent(input, "contato_valor", contato_valor);
                    putPresent(input, "plano", plano);
                    putPresent(input, "slug", slug);
                    ProvisionedWorkspace created = provisioning.create(input, slot);
                    return new WebEndpointResponse<>(new AdminCreatedResponse(created.id(), created.slug()), 201);
                }
                case LINK_ATIVO -> provisioning.setLinkAtivo(slugOf(slug), flag(ativo), slot);
                case AUTOFILL_ATIVO -> provisioning.setAutofillAtivo(slugOf(slug), flag(ativo), slot);
                case AUTOFILL_GLOBAL -> provisioning.setGlobalAutofill(flag(ativo), slot);
                case GASTO_PROVEDOR -> provisioning.setGastoProvedor(flag(ativo), slot);
                case EMAIL_TESTE -> {
                    return testEmail(email, slot);
                }
                default -> throw new ProvisioningException(Reason.NOT_FOUND, "operacao");
            }
            return new WebEndpointResponse<>(WebEndpointResponse.STATUS_NO_CONTENT);
        } catch (ProvisioningException e) {
            return refused(e);
        }
    }

    /** Sent → 204; an address off the allowed list → 400; not sent → 503, never with provider details. */
    private WebEndpointResponse<Object> testEmail(@Nullable String email, TokenSlot slot) {
        return switch (operatorEmails.sendTest(email, slot)) {
            case SENT -> new WebEndpointResponse<>(WebEndpointResponse.STATUS_NO_CONTENT);
            case NOT_ALLOWED -> new WebEndpointResponse<>(new AdminErrorResponse(Reason.INVALID.name(), "email"),
                    WebEndpointResponse.STATUS_BAD_REQUEST);
            case NOT_SENT -> new WebEndpointResponse<>(new AdminErrorResponse(UNAVAILABLE, "email"),
                    WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
        };
    }

    /** The slot the security chain authenticated; anything else is a wiring fault, never a pass. */
    private static TokenSlot slot(SecurityContext security) {
        Principal principal = security.getPrincipal();
        if (principal == null) {
            throw new IllegalStateException("an admin operation ran without an authenticated operator");
        }
        return TokenSlot.valueOf(principal.getName());
    }

    private static void putPresent(Map<String, Object> input, String field, @Nullable String value) {
        if (value != null) {
            input.put(field, value);
        }
    }

    private static String slugOf(@Nullable String slug) {
        if (slug != null && !slug.isBlank()) {
            return slug;
        }
        throw new ProvisioningException(Reason.INVALID, "slug");
    }

    /** Exactly {@code "true"} or {@code "false"}: no other spelling, case or padding. */
    private static boolean flag(@Nullable String ativo) {
        if ("true".equals(ativo)) {
            return true;
        }
        if ("false".equals(ativo)) {
            return false;
        }
        throw new ProvisioningException(Reason.INVALID, "ativo");
    }

    private static WebEndpointResponse<Object> refused(ProvisioningException e) {
        int status = switch (e.reason()) {
            case INVALID -> WebEndpointResponse.STATUS_BAD_REQUEST;
            case NOT_FOUND -> WebEndpointResponse.STATUS_NOT_FOUND;
            case CONFLICT -> 409;
        };
        return new WebEndpointResponse<>(new AdminErrorResponse(e.reason().name(), e.field()), status);
    }
}

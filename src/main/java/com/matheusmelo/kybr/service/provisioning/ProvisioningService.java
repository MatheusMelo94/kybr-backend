package com.matheusmelo.kybr.service.provisioning;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.Ator;
import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.ResumoAuditoria;
import com.matheusmelo.kybr.model.link.TipoAuditoria;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import com.matheusmelo.kybr.model.workspace.DocumentoTipo;
import com.matheusmelo.kybr.model.workspace.Plano;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.TransactionRetry;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.service.provisioning.ProvisioningException.Reason;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal provisioning and the operator's switches. Every input is re-validated here as a typed
 * command, whatever the caller's binding did: only known fields, strings only, each against its
 * rule. Every operation writes an audit event with {@code ator=operador} and the admin token slot,
 * in the same transaction as its change.
 *
 * <ul>
 *   <li>{@code nome}: 1–80 characters of letters, digits, spaces and {@code ' . & , ( ) -}; no
 *       reserved word.
 *   <li>{@code contato_tipo} / {@code contato_valor}: a plain ASCII e-mail address (at most 254
 *       characters), or an absolute {@code https:} URL with a host and no user info.
 *   <li>{@code plano}: {@code interno}, {@code teste} or {@code pago}.
 *   <li>{@code slug}: only when explicit slugs are allowed (never in prod): well formed, not
 *       reserved, unused. Otherwise the slug is assigned, retried on a collision.
 * </ul>
 */
@Service
public class ProvisioningService {

    static final Set<String> FIELDS = Set.of("nome", "categoria", "contato_tipo", "contato_valor", "plano", "slug");
    private static final int MAX_SLUG_ATTEMPTS = 5;
    private static final Pattern NOME = Pattern.compile("[\\p{L}\\p{M}\\p{N} '.&,()-]{1,80}");
    private static final Pattern CATEGORIA = Pattern.compile("[\\p{L}\\p{N} _-]{1,40}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+");
    private static final Pattern ASCII_URL = Pattern.compile("[\\x21-\\x7E]{1,2048}");

    private final WorkspaceRepository workspaces;
    private final AuditEventRepository audits;
    private final RuntimeFlagsService flags;
    private final SlugAssigner slugs;
    private final TransactionTemplate transactions;
    private final boolean allowExplicitSlug;
    private final Clock clock;

    @Autowired
    public ProvisioningService(WorkspaceRepository workspaces, AuditEventRepository audits, RuntimeFlagsService flags,
            SlugAssigner slugs, MongoTransactionManager transactionManager, KybrProperties properties, Clock clock) {
        this(workspaces, audits, flags, slugs, transactionManager, properties.admin().allowExplicitSlug(), clock);
    }

    ProvisioningService(WorkspaceRepository workspaces, AuditEventRepository audits, RuntimeFlagsService flags,
            SlugAssigner slugs, MongoTransactionManager transactionManager, boolean allowExplicitSlug, Clock clock) {
        this.workspaces = workspaces;
        this.audits = audits;
        this.flags = flags;
        this.slugs = slugs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.allowExplicitSlug = allowExplicitSlug;
        this.clock = clock;
    }

    /** Creates a workspace with an active link and autofill, the default policy and documents. */
    public ProvisionedWorkspace create(Map<String, Object> input, TokenSlot slot) {
        for (String field : input.keySet()) {
            if (!FIELDS.contains(field)) {
                throw invalid(field);
            }
        }
        String nome = Normalizer.normalize(requiredString(input, "nome"), Normalizer.Form.NFC).strip();
        if (!NOME.matcher(nome).matches() || slugs.isReserved(nome)) {
            throw invalid("nome");
        }
        String categoria = requiredString(input, "categoria").strip();
        if (!CATEGORIA.matcher(categoria).matches()) {
            throw invalid("categoria");
        }
        ContatoVendedor contato = contato(requiredString(input, "contato_tipo"), requiredString(input, "contato_valor"));
        Plano plano = new Plano(plano(requiredString(input, "plano")));
        String explicit = explicitSlug(input);

        for (int attempt = 1; ; attempt++) {
            String slug = explicit != null ? explicit : slugs.assign(nome);
            Workspace workspace = new Workspace(UUID.randomUUID(), nome, slug, categoria, contato, plano, true, true,
                    DefaultPolicies.forCategory(categoria), defaultDocuments(), clock.instant());
            try {
                inTransaction(() -> {
                    workspaces.insert(workspace);
                    audit(TipoAuditoria.WORKSPACE_CRIADO, workspace.id(), slot,
                            new ResumoAuditoria(null, null, null, slug, explicit != null, null));
                });
                return new ProvisionedWorkspace(workspace.id(), slug);
            } catch (DuplicateKeyException e) {
                if (explicit != null) {
                    throw new ProvisioningException(Reason.CONFLICT, "slug");
                }
                if (attempt >= MAX_SLUG_ATTEMPTS) {
                    throw new IllegalStateException("no free slug after " + MAX_SLUG_ATTEMPTS + " attempts", e);
                }
            }
        }
    }

    /** One workspace by slug, for the operator; not found is a {@link Reason#NOT_FOUND} refusal. */
    public Workspace workspace(String slug) {
        return bySlug(slug);
    }

    /** The link's kill switch: off, the link answers "not found" to new activity. */
    public void setLinkAtivo(String slug, boolean ativo, TokenSlot slot) {
        inTransaction(() -> {
            Workspace current = bySlug(slug);
            workspaces.save(new Workspace(current.id(), current.nome(), current.slug(), current.categoria(),
                    current.contatoVendedor(), current.plano(), ativo, current.autofillAtivo(), current.politica(),
                    current.documentosHabilitados(), current.criadoEm()));
            audit(TipoAuditoria.LINK_ATIVO_ALTERADO, current.id(), slot, switched(current.slug(), ativo));
        });
    }

    /** The workspace's company-lookup switch. */
    public void setAutofillAtivo(String slug, boolean ativo, TokenSlot slot) {
        inTransaction(() -> {
            Workspace current = bySlug(slug);
            workspaces.save(new Workspace(current.id(), current.nome(), current.slug(), current.categoria(),
                    current.contatoVendedor(), current.plano(), current.linkAtivo(), ativo, current.politica(),
                    current.documentosHabilitados(), current.criadoEm()));
            audit(TipoAuditoria.AUTOFILL_ALTERADO, current.id(), slot, switched(current.slug(), ativo));
        });
    }

    /** The company-lookup switch for every workspace. */
    public void setGlobalAutofill(boolean ativo, TokenSlot slot) {
        inTransaction(() -> {
            flags.setAutofill(ativo);
            audit(TipoAuditoria.AUTOFILL_GLOBAL_ALTERADO, null, slot, switched(null, ativo));
        });
    }

    /** The provider-spend brake: off, lookups are cache-only and verifications defer. */
    public void setGastoProvedor(boolean ativo, TokenSlot slot) {
        inTransaction(() -> {
            flags.setProviderSpend(ativo);
            audit(TipoAuditoria.GASTO_PROVEDOR_ALTERADO, null, slot, switched(null, ativo));
        });
    }

    /** One transaction, re-run on a transient transaction error such as a write conflict. */
    private void inTransaction(Runnable work) {
        TransactionRetry.DEFAULT.execute(() -> transactions.execute(status -> {
            work.run();
            return null;
        }));
    }

    private Workspace bySlug(String slug) {
        return workspaces.findBySlug(slug).orElseThrow(() -> new ProvisioningException(Reason.NOT_FOUND, "slug"));
    }

    private void audit(TipoAuditoria tipo, UUID workspaceId, TokenSlot slot, ResumoAuditoria resumo) {
        Instant now = clock.instant();
        audits.insert(new AuditEvent(UUID.randomUUID(), workspaceId, tipo, Ator.operador, null, now, null, true, null,
                slot, resumo));
    }

    private static ResumoAuditoria switched(String slug, boolean ativo) {
        return new ResumoAuditoria(null, null, null, slug, null, ativo);
    }

    private String explicitSlug(Map<String, Object> input) {
        if (!input.containsKey("slug")) {
            return null;
        }
        if (!allowExplicitSlug || !(input.get("slug") instanceof String slug)
                || !Workspace.SLUG_PATTERN.matcher(slug).matches() || slugs.isReserved(slug)) {
            throw invalid("slug");
        }
        if (workspaces.findBySlug(slug).isPresent()) {
            throw new ProvisioningException(Reason.CONFLICT, "slug");
        }
        return slug;
    }

    private static ContatoVendedor contato(String tipo, String valor) {
        ContatoVendedor.Tipo parsed = Arrays.stream(ContatoVendedor.Tipo.values())
                .filter(candidate -> candidate.name().equals(tipo)).findFirst()
                .orElseThrow(() -> invalid("contato_tipo"));
        if (parsed == ContatoVendedor.Tipo.email) {
            if (valor.length() > 254 || !EMAIL.matcher(valor).matches()) {
                throw invalid("contato_vendedor");
            }
            return new ContatoVendedor(parsed, valor);
        }
        return new ContatoVendedor(parsed, canonicalHttpsUrl(valor));
    }

    /**
     * The canonical form of an absolute {@code https:} URL with a host and no user info: scheme and
     * host lower case, the default port dropped, leading slashes of the path collapsed into one (so the
     * host never changes), dot segments removed, an empty path as {@code /}, the fragment dropped. Anything else is invalid.
     */
    private static String canonicalHttpsUrl(String valor) {
        if (!ASCII_URL.matcher(valor).matches()) {
            throw invalid("contato_vendedor");
        }
        try {
            URI uri = new URI(valor);
            if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                throw invalid("contato_vendedor");
            }
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            // Leading slashes collapsed: "//other.host/x" would resolve as a reference to another host.
            String path = "/" + (uri.getRawPath() == null ? "" : uri.getRawPath().replaceFirst("^/+", ""));
            URI canonical = new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), port, "/", null, null)
                    .resolve(path).normalize();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return canonical.toASCIIString() + query;
        } catch (URISyntaxException e) {
            throw invalid("contato_vendedor");
        }
    }

    private static Plano.Tipo plano(String value) {
        return Arrays.stream(Plano.Tipo.values()).filter(tipo -> tipo.name().equals(value)).findFirst()
                .orElseThrow(() -> invalid("plano"));
    }

    private static List<DocumentoTipo> defaultDocuments() {
        return Arrays.stream(DocumentoTipo.values()).filter(tipo -> tipo != DocumentoTipo.PROCURACAO).toList();
    }

    private static String requiredString(Map<String, Object> input, String field) {
        if (input.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw invalid(field);
    }

    private static ProvisioningException invalid(String field) {
        return new ProvisioningException(Reason.INVALID, field);
    }
}

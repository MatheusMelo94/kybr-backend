package com.matheusmelo.kybr.acceptance.link;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The link frontend's response parser, ported to a check: every 2xx body of the seller link must be
 * one the frontend parses without rejecting, coercing or dropping anything. Where the frontend is
 * lenient (it drops an unknown item, coerces an unknown block reason, strips unknown keys), this
 * check fails instead, so the backend never relies on that leniency.
 *
 * <p>Ported from the frontend's public-link response schemas at commit {@code 27a781c}, with one
 * agreed relaxation the frontend adopts before it talks to this backend: a {@code processing}
 * result may carry only {@code responsavel} and {@code email_contato_mascarado} (the case read
 * after the seller saved a contact e-mail); and one agreed addition the frontend adopts with the
 * document upload: a document item may carry {@code formato_envio} ({@code pdf} or {@code fotos})
 * once a file was sent for its type, and, with the asynchronous verification, {@code situacao_envio}
 * ({@code verificando}, {@code aceito} or {@code recusado}), {@code documentos_recusados} and, only
 * with {@code recusado}, {@code motivo_recusa} ({@code CONTEUDO_NAO_PERMITIDO},
 * {@code ARQUIVO_INVALIDO} or {@code VERIFICACAO_FALHOU}). Re-sync this class whenever the frontend's
 * schemas change.
 *
 * <p>Also checks the backend's own case-read shape rules: which keys each state may carry, and
 * that no internal vocabulary (raw checks, verdict words, policy names) reaches the seller.
 */
public final class FrontendContract implements Filter {

    static final Pattern STRICT_EMAIL = Pattern.compile("^[A-Za-z0-9._+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22,64}$");
    static final Pattern UF = Pattern.compile("^[A-Z]{2}$");
    static final Pattern OFFSET_DATE_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$");
    /** An 11-digit CPF, plain or punctuated, not part of a longer number. */
    static final Pattern UNMASKED_CPF = Pattern.compile("(?<![0-9])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?![0-9])");
    /** Words of the internal verdict and policy vocabulary that must never reach the seller. */
    static final List<String> INTERNAL_WORDS = List.of("checagens", "veredito", "\"motivos\"", "politica", "aprovar",
            "revisar", "bloquear", "approve_limit", "lease_owner", "processamento", "token_hash");

    private static final Set<String> ESTADOS = Set.of("processing", "pending", "approved", "blocked");
    private static final Set<String> SITUACOES = Set.of("inapta", "suspensa", "baixada", "nula");
    private static final Set<String> DOCUMENT_TYPES = Set.of("CONTRATO_SOCIAL_OU_CCMEI", "PROCURACAO",
            "COMPROVANTE_ENDERECO", "CARTAO_CNPJ", "RESPONSAVEL");
    private static final Set<String> RESULT_KEYS = Set.of("razao_social", "responsavel", "consultado_em", "variante",
            "prazo_revisao", "email_contato_mascarado", "confirmacoes", "itens", "motivo");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 2xx bodies checked by every instance, for the test pass report. */
    static final AtomicInteger TOTAL_CHECKED = new AtomicInteger();

    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger checked = new AtomicInteger();

    /** Which seller-link operation a request is, or null when it isn't one. */
    public enum Route { INFO, LOOKUP, VERIFY, CASE, CONTACT }

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification responseSpec,
            FilterContext ctx) {
        Response response = ctx.next(request, responseSpec);
        Route route = route(request.getMethod(), request.getURI());
        if (route != null && response.statusCode() / 100 == 2) {
            checked.incrementAndGet();
            TOTAL_CHECKED.incrementAndGet();
            for (String violation : check(route, response.statusCode(), response.asString())) {
                violations.add(request.getMethod() + " " + request.getURI() + " -> " + response.statusCode() + ": "
                        + violation);
            }
        }
        return response;
    }

    int checked() {
        return checked.get();
    }

    /** Fails on any violation recorded since the last call, then forgets them. */
    void verifyAndReset() {
        List<String> found = List.copyOf(violations);
        violations.clear();
        checked.set(0);
        Assertions.assertThat(found).as("2xx bodies the link frontend would reject, coerce or drop").isEmpty();
    }

    static Route route(String method, String uri) {
        String path;
        try {
            path = new URI(uri).getRawPath();
        } catch (URISyntaxException e) {
            return null;
        }
        if (path == null || !path.startsWith("/public/v/")) {
            return null;
        }
        String[] parts = path.substring("/public/v/".length()).split("/", -1);
        return switch (method) {
            case "GET" -> parts.length == 1 ? Route.INFO
                    : parts.length == 2 && parts[1].equals("lookup") ? Route.LOOKUP
                    : parts.length == 3 && parts[1].equals("cases") ? Route.CASE : null;
            case "POST" -> parts.length == 2 && parts[1].equals("verify") ? Route.VERIFY : null;
            case "PUT" -> parts.length == 4 && parts[1].equals("cases") && parts[3].equals("contact") ? Route.CONTACT
                    : null;
            default -> null;
        };
    }

    /** The violations in one 2xx body of {@code route}; empty when the frontend takes it as is. */
    public static List<String> check(Route route, int status, String body) {
        List<String> out = new ArrayList<>();
        if (route == Route.CONTACT) {
            if (status != 204 || !body.isEmpty()) {
                out.add("contact: expected 204 with an empty body");
            }
            return out;
        }
        Object parsed;
        try {
            parsed = JSON.readValue(body, Object.class);
        } catch (RuntimeException e) {
            out.add("not JSON");
            return out;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            out.add("not a JSON object");
            return out;
        }
        switch (route) {
            case INFO -> info(map, out);
            case LOOKUP -> lookup(map, out);
            case VERIFY -> verify(status, map, out);
            case CASE -> {
                caseRead(map, out);
                for (String word : INTERNAL_WORDS) {
                    if (body.contains(word)) {
                        out.add("case: internal vocabulary in the body: " + word);
                    }
                }
            }
            default -> out.add("unexpected route");
        }
        return out;
    }

    private static void info(Map<?, ?> body, List<String> out) {
        keys("info", body, Set.of("nome", "contato_vendedor"), Set.of("nome", "contato_vendedor"), out);
        nonEmpty("info.nome", body.get("nome"), out);
        if (!(body.get("contato_vendedor") instanceof Map<?, ?> contato)) {
            out.add("info.contato_vendedor: not an object");
            return;
        }
        keys("info.contato_vendedor", contato, Set.of("tipo", "valor"), Set.of("tipo", "valor"), out);
        Object tipo = contato.get("tipo");
        Object valor = contato.get("valor");
        if ("email".equals(tipo)) {
            if (!(valor instanceof String email) || email.length() > 254 || !STRICT_EMAIL.matcher(email).matches()) {
                out.add("info.contato_vendedor: an e-mail the frontend's strict rule refuses");
            }
        } else if ("url".equals(tipo)) {
            if (!(valor instanceof String url) || !safeHttpsUrl(url)) {
                out.add("info.contato_vendedor: not an absolute https URL without credentials");
            }
        } else {
            out.add("info.contato_vendedor.tipo: neither email nor url");
        }
    }

    private static void lookup(Map<?, ?> body, List<String> out) {
        Set<String> all = Set.of("razao_social", "municipio", "uf", "cnae");
        keys("lookup", body, all, all, out);
        nonEmpty("lookup.razao_social", body.get("razao_social"), out);
        noUnmaskedCpf("lookup.razao_social", body.get("razao_social"), out);
        nonEmpty("lookup.municipio", body.get("municipio"), out);
        matches("lookup.uf", body.get("uf"), UF, out);
        if (body.get("cnae") instanceof Map<?, ?> cnae) {
            keys("lookup.cnae", cnae, Set.of("descricao"), Set.of("descricao"), out);
            nonEmpty("lookup.cnae.descricao", cnae.get("descricao"), out);
        } else {
            out.add("lookup.cnae: not an object");
        }
    }

    private static void verify(int status, Map<?, ?> body, List<String> out) {
        if (status != 201) {
            out.add("verify: expected 201");
        }
        keys("verify", body, Set.of("token"), Set.of("token"), out);
        matches("verify.token", body.get("token"), TOKEN, out);
    }

    private static void caseRead(Map<?, ?> body, List<String> out) {
        keys("case", body, Set.of("estado", "criado_em"), Set.of("estado", "criado_em", "resultado"), out);
        Object estado = body.get("estado");
        if (!ESTADOS.contains(estado)) {
            out.add("case.estado: unknown value");
            return;
        }
        dateTime("case.criado_em", body.get("criado_em"), true, out);
        Object resultado = body.get("resultado");
        if (!"processing".equals(estado) && resultado == null) {
            out.add("case.resultado: required once the case has a result");
        }
        if (resultado == null) {
            return;
        }
        if (!(resultado instanceof Map<?, ?> result)) {
            out.add("case.resultado: not an object");
            return;
        }
        resultado((String) estado, result, out);
        if ("pending".equals(estado) && result.get("prazo_revisao") == null) {
            out.add("case.resultado.prazo_revisao: required on pending");
        }
    }

    private static void resultado(String estado, Map<?, ?> r, List<String> out) {
        keys("resultado", r, Set.of("responsavel"), RESULT_KEYS, out);
        nonEmpty("resultado.responsavel", r.get("responsavel"), out);
        if (r.containsKey("razao_social")) {
            nonEmpty("resultado.razao_social", r.get("razao_social"), out);
            noUnmaskedCpf("resultado.razao_social", r.get("razao_social"), out);
        }
        dateTime("resultado.consultado_em", r.get("consultado_em"), false, out);
        dateTime("resultado.prazo_revisao", r.get("prazo_revisao"), false, out);
        boolean variante = r.containsKey("variante");
        if (variante && !"DADOS_NAO_ENCONTRADOS".equals(r.get("variante"))) {
            out.add("resultado.variante: unknown value");
        }
        if (r.containsKey("email_contato_mascarado")
                && !(r.get("email_contato_mascarado") instanceof String email && !email.isEmpty()
                        && email.length() <= 254)) {
            out.add("resultado.email_contato_mascarado: not a string of 1 to 254 characters");
        }
        if (r.containsKey("confirmacoes")) {
            list("resultado.confirmacoes", r.get("confirmacoes"), out).forEach(c -> confirmacao(c, out));
        }
        if (r.containsKey("itens")) {
            list("resultado.itens", r.get("itens"), out).forEach(i -> item(i, out));
        }
        if (r.containsKey("motivo")) {
            motivo(r.get("motivo"), out);
        }
        boolean processingWithContactOnly = "processing".equals(estado)
                && Set.of("responsavel", "email_contato_mascarado").containsAll(keySet(r))
                && r.containsKey("email_contato_mascarado");
        if (!variante && !processingWithContactOnly) {
            for (String key : List.of("razao_social", "consultado_em")) {
                if (!r.containsKey(key)) {
                    out.add("resultado." + key + ": required without DADOS_NAO_ENCONTRADOS");
                }
            }
        }
        if (!r.containsKey("razao_social") && r.get("motivo") instanceof Map<?, ?> motivo
                && !"GENERICO".equals(motivo.get("codigo"))) {
            out.add("resultado.motivo: a reason other than GENERICO without company data (coerced)");
        }
        stateShape(estado, r, variante, out);
    }

    /** The backend's own shape rules per state. */
    private static void stateShape(String estado, Map<?, ?> r, boolean variante, List<String> out) {
        Set<String> keys = keySet(r);
        switch (estado) {
            case "processing" -> {
                if (!Set.of("responsavel", "email_contato_mascarado", "variante").containsAll(keys)) {
                    out.add("shape: processing carries only responsavel, the masked e-mail and the variant");
                }
            }
            case "pending" -> forbid(keys, Set.of("confirmacoes", "motivo"), "pending", out);
            case "approved" -> forbid(keys, Set.of("itens", "motivo", "prazo_revisao"), "approved", out);
            case "blocked" -> forbid(keys, Set.of("itens", "confirmacoes", "prazo_revisao"), "blocked", out);
            default -> out.add("shape: unknown state");
        }
        if (variante) {
            forbid(keys, Set.of("razao_social", "consultado_em", "confirmacoes"), "variant", out);
            for (Object item : r.get("itens") instanceof List<?> items ? items : List.of()) {
                if (item instanceof Map<?, ?> m && !"DOCUMENTO_SOLICITADO".equals(m.get("codigo"))) {
                    out.add("shape: a variant shows only requested-document items");
                }
            }
        }
    }

    private static void confirmacao(Object element, List<String> out) {
        if (!(element instanceof Map<?, ?> c)) {
            out.add("confirmacoes[]: not an object (dropped)");
            return;
        }
        Object codigo = c.get("codigo");
        if ("SITUACAO_ATIVA".equals(codigo) || "RESPONSAVEL_CONFERE".equals(codigo)) {
            keys("confirmacoes[" + codigo + "]", c, Set.of("codigo"), Set.of("codigo"), out);
        } else if ("IE_ATIVA".equals(codigo)) {
            keys("confirmacoes[IE_ATIVA]", c, Set.of("codigo", "uf"), Set.of("codigo", "uf"), out);
            matches("confirmacoes[IE_ATIVA].uf", c.get("uf"), UF, out);
        } else {
            out.add("confirmacoes[]: unknown code (dropped): " + codigo);
        }
    }

    private static void item(Object element, List<String> out) {
        if (!(element instanceof Map<?, ?> i)) {
            out.add("itens[]: not an object (dropped)");
            return;
        }
        Object codigo = i.get("codigo");
        String where = "itens[" + codigo + "]";
        switch (String.valueOf(codigo)) {
            case "DOCUMENTO_RESPONSAVEL", "DOCUMENTO_SOLICITADO" -> {
                Set<String> required = Set.of("codigo", "documento_tipo", "documentos_enviados");
                keys(where, i, required, Set.of("codigo", "documento_tipo", "documentos_enviados", "formato_envio",
                        "situacao_envio", "documentos_recusados", "motivo_recusa"), out);
                if (i.containsKey("formato_envio") && !Set.of("pdf", "fotos").contains(i.get("formato_envio"))) {
                    out.add(where + ".formato_envio: not pdf or fotos (rejected)");
                }
                if (i.containsKey("situacao_envio")
                        && !Set.of("verificando", "aceito", "recusado").contains(i.get("situacao_envio"))) {
                    out.add(where + ".situacao_envio: unknown (rejected)");
                }
                if (i.containsKey("documentos_recusados")
                        && (!(i.get("documentos_recusados") instanceof Integer refused) || refused < 0)) {
                    out.add(where + ".documentos_recusados: not an integer of at least 0");
                }
                if (i.containsKey("motivo_recusa") && (!"recusado".equals(i.get("situacao_envio"))
                        || !Set.of("CONTEUDO_NAO_PERMITIDO", "ARQUIVO_INVALIDO", "VERIFICACAO_FALHOU")
                                .contains(i.get("motivo_recusa")))) {
                    out.add(where + ".motivo_recusa: unknown or without recusado (rejected)");
                }
                if (!DOCUMENT_TYPES.contains(i.get("documento_tipo"))) {
                    out.add(where + ".documento_tipo: unknown or missing (dropped)");
                }
                if (!(i.get("documentos_enviados") instanceof Integer sent) || sent < 0) {
                    out.add(where + ".documentos_enviados: not an integer of at least 0");
                }
            }
            case "IE_NAO_ENCONTRADA", "IE_SUSPENSA" -> {
                keys(where, i, Set.of("codigo"), Set.of("codigo", "url_regularizacao"), out);
                govBr(where, i, out);
            }
            case "SITUACAO_IRREGULAR" -> {
                keys(where, i, Set.of("codigo", "situacao"), Set.of("codigo", "situacao", "url_regularizacao"), out);
                if (!SITUACOES.contains(i.get("situacao"))) {
                    out.add(where + ".situacao: unknown or missing (dropped)");
                }
                govBr(where, i, out);
            }
            case "EMPRESA_RECENTE" -> {
                keys(where, i, Set.of("codigo", "dias"), Set.of("codigo", "dias"), out);
                if (!(i.get("dias") instanceof Integer dias) || dias < 1) {
                    out.add(where + ".dias: not an integer of at least 1 (dropped)");
                }
            }
            case "ATIVIDADE_FORA_DA_CATEGORIA", "MEI" -> keys(where, i, Set.of("codigo"), Set.of("codigo"), out);
            default -> out.add("itens[]: unknown code (dropped): " + codigo);
        }
    }

    private static void motivo(Object value, List<String> out) {
        if (!(value instanceof Map<?, ?> m)) {
            out.add("motivo: not an object (coerced to GENERICO)");
            return;
        }
        Object codigo = m.get("codigo");
        switch (String.valueOf(codigo)) {
            case "SITUACAO_IRREGULAR" -> {
                keys("motivo", m, Set.of("codigo", "situacao"), Set.of("codigo", "situacao", "url_regularizacao"), out);
                if (!SITUACOES.contains(m.get("situacao"))) {
                    out.add("motivo.situacao: unknown or missing (coerced to GENERICO)");
                }
                govBr("motivo", m, out);
            }
            case "IE_NAO_ENCONTRADA", "IE_SUSPENSA" -> {
                keys("motivo", m, Set.of("codigo"), Set.of("codigo", "url_regularizacao"), out);
                govBr("motivo", m, out);
            }
            case "RESPONSAVEL_NAO_SOCIO", "EMPRESA_RECENTE", "ATIVIDADE_FORA_DA_CATEGORIA", "MEI", "GENERICO" ->
                    keys("motivo", m, Set.of("codigo"), Set.of("codigo"), out);
            default -> out.add("motivo: unknown code (coerced to GENERICO): " + codigo);
        }
    }

    private static void govBr(String where, Map<?, ?> m, List<String> out) {
        if (!m.containsKey("url_regularizacao")) {
            return;
        }
        if (!(m.get("url_regularizacao") instanceof String url) || !safeHttpsUrl(url)) {
            out.add(where + ".url_regularizacao: not an https URL (dropped)");
            return;
        }
        String host = URI.create(url).getHost().toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("gov.br") && !host.endsWith(".gov.br")) {
            out.add(where + ".url_regularizacao: not on gov.br (dropped)");
        }
    }

    static boolean safeHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static List<?> list(String where, Object value, List<String> out) {
        if (value instanceof List<?> l) {
            return l;
        }
        out.add(where + ": not an array");
        return List.of();
    }

    private static void keys(String where, Map<?, ?> map, Set<String> required, Set<String> allowed, List<String> out) {
        for (String key : required) {
            if (!map.containsKey(key) || map.get(key) == null) {
                out.add(where + "." + key + ": missing");
            }
        }
        for (Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                out.add(where + "." + key + ": not in the contract");
            }
        }
    }

    private static void forbid(Set<String> keys, Set<String> forbidden, String state, List<String> out) {
        for (String key : forbidden) {
            if (keys.contains(key)) {
                out.add("shape: " + state + " never carries " + key);
            }
        }
    }

    private static Set<String> keySet(Map<?, ?> map) {
        Set<String> keys = new java.util.HashSet<>();
        map.keySet().forEach(k -> keys.add(String.valueOf(k)));
        return keys;
    }

    private static void nonEmpty(String where, Object value, List<String> out) {
        if (!(value instanceof String s) || s.isEmpty()) {
            out.add(where + ": not a non-empty string");
        }
    }

    private static void matches(String where, Object value, Pattern pattern, List<String> out) {
        if (!(value instanceof String s) || !pattern.matcher(s).matches()) {
            out.add(where + ": does not match " + pattern.pattern());
        }
    }

    private static void dateTime(String where, Object value, boolean required, List<String> out) {
        if (value == null) {
            if (required) {
                out.add(where + ": missing");
            }
            return;
        }
        matches(where, value, OFFSET_DATE_TIME, out);
    }

    private static void noUnmaskedCpf(String where, Object value, List<String> out) {
        if (value instanceof String s && UNMASKED_CPF.matcher(s).find()) {
            out.add(where + ": an unmasked CPF (the frontend masks it again)");
        }
    }
}

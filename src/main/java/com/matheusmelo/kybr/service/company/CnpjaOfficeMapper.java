package com.matheusmelo.kybr.service.company;

import com.matheusmelo.kybr.model.link.Regime;
import com.matheusmelo.kybr.model.link.SituacaoCadastral;
import com.matheusmelo.kybr.util.Uf;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Maps the provider's company ("office") response to the port's types. Only the fields Kybr uses
 * are read; everything else in the body is ignored and dropped with it. A missing, null or blank
 * value maps to null, so a caller can tell an incomplete record from a complete one. The provider is
 * untrusted: a value of the wrong shape (a state that isn't a state code, an overlong text, an
 * activity code that isn't seven digits) maps to null too, so it follows the incomplete-record path
 * and is never cached or served.
 */
final class CnpjaOfficeMapper {

    static final int MAX_RAZAO_SOCIAL = 300;
    static final int MAX_MUNICIPIO = 120;
    static final int MAX_CNAE_DESCRICAO = 300;
    static final int MAX_NOME_SOCIO = 300;

    private CnpjaOfficeMapper() {
    }

    static BasicCompany basic(JsonNode office) {
        return new BasicCompany(text(office.path("company").path("name"), MAX_RAZAO_SOCIAL),
                text(office.path("address").path("city"), MAX_MUNICIPIO), uf(office.path("address").path("state")),
                text(office.path("mainActivity").path("text"), MAX_CNAE_DESCRICAO));
    }

    static CompanyRecord record(JsonNode office) {
        JsonNode company = office.path("company");
        return new CompanyRecord(
                text(company.path("name"), MAX_RAZAO_SOCIAL),
                text(office.path("address").path("city"), MAX_MUNICIPIO),
                uf(office.path("address").path("state")),
                cnaeCodigo(office.path("mainActivity").path("id")),
                text(office.path("mainActivity").path("text"), MAX_CNAE_DESCRICAO),
                situacao(office.path("status").path("id")),
                date(office.path("founded")),
                regime(company),
                inscricoes(office.path("registrations")),
                partners(company.path("members")));
    }

    /** The stripped string; null when missing, blank or longer than {@code max}. */
    private static String text(JsonNode node, int max) {
        if (!node.isString()) {
            return null;
        }
        String value = node.stringValue().strip();
        return value.isEmpty() || value.length() > max ? null : value;
    }

    /** The upper-cased state code; null unless it's a state or the Federal District. */
    private static String uf(JsonNode node) {
        String value = text(node, 2);
        String upper = value == null ? null : value.toUpperCase(Locale.ROOT);
        return Uf.isValid(upper) ? upper : null;
    }

    /** The main activity code, as seven digits; null in any other shape. */
    private static String cnaeCodigo(JsonNode node) {
        String value = node.isIntegralNumber() ? String.format(Locale.ROOT, "%07d", node.longValue()) : text(node, 7);
        return value != null && value.matches("[0-9]{7}") ? value : null;
    }

    /** The registration status by the provider's numeric code; null for a code Kybr doesn't know. */
    private static SituacaoCadastral situacao(JsonNode node) {
        if (!node.isIntegralNumber()) {
            return null;
        }
        return switch (node.intValue()) {
            case 1 -> SituacaoCadastral.nula;
            case 2 -> SituacaoCadastral.ativa;
            case 3 -> SituacaoCadastral.suspensa;
            case 4 -> SituacaoCadastral.inapta;
            case 8 -> SituacaoCadastral.baixada;
            default -> null;
        };
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node, 32);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** MEI when the company opted into SIMEI, Simples when into Simples, null when neither was reported. */
    private static Regime regime(JsonNode company) {
        JsonNode simei = company.path("simei");
        JsonNode simples = company.path("simples");
        if (simei.path("optant").asBoolean(false)) {
            return Regime.MEI;
        }
        if (simples.path("optant").asBoolean(false)) {
            return Regime.SIMPLES;
        }
        return simei.isObject() || simples.isObject() ? Regime.OUTRO : null;
    }

    private static List<CompanyRecord.InscricaoEstadual> inscricoes(JsonNode registrations) {
        List<CompanyRecord.InscricaoEstadual> result = new ArrayList<>();
        for (JsonNode registration : registrations) {
            result.add(new CompanyRecord.InscricaoEstadual(uf(registration.path("state")),
                    registration.path("enabled").asBoolean(false)));
        }
        return result;
    }

    private static List<String> partners(JsonNode members) {
        List<String> names = new ArrayList<>();
        for (JsonNode member : members) {
            String name = text(member.path("person").path("name"), MAX_NOME_SOCIO);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }
}

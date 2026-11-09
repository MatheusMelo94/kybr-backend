package com.matheusmelo.kybr.service.email;

import com.matheusmelo.kybr.util.SellerText;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A placeholder's value. Each type decides how its value is made safe to show, so a template never
 * interpolates raw text.
 */
public sealed interface TemplateValue {

    /** The hosts and paths an e-mail may link to: the seller link, and the panel. */
    List<String> ALLOWED_LINK_PREFIXES = List.of("https://kybr.com.br/v/", "https://app.kybr.com.br/");

    /** The text that replaces the placeholder in the plain part (the HTML part escapes it). */
    String rendered();

    /** Text the application chose (fixed internal copy); never seller-, provider- or user-typed. */
    record Texto(String value) implements TemplateValue {
        public Texto {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String rendered() {
            return value;
        }
    }

    /** A seller's {@code responsavel}: cleaned, link-neutralised and bounded. */
    record TextoVendedor(String value) implements TemplateValue {
        @Override
        public String rendered() {
            return Objects.requireNonNullElse(SellerText.responsavel(value), "");
        }
    }

    /**
     * A masked address ({@code j•••@example.com}). Its domain is typed by whoever set the address, so
     * it is link-neutralised like seller text ({@code example[.]com}) and a mail client can't turn it
     * into a link.
     */
    record EmailMascarado(String value) implements TemplateValue {
        public EmailMascarado {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String rendered() {
            return SellerText.clean(value, SellerText.EMAIL_MAX);
        }
    }

    /** A company's razão social: cleaned, CPF-masked and bounded. */
    record RazaoSocial(String value) implements TemplateValue {
        @Override
        public String rendered() {
            return Objects.requireNonNullElse(SellerText.razaoSocial(value), "");
        }
    }

    /** A moment, shown as {@code dd/MM/yyyy às HH:mm} in São Paulo time. */
    record DataHora(Instant value) implements TemplateValue {
        private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
        private static final DateTimeFormatter FORMAT =
                DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR")).withZone(SAO_PAULO);

        public DataHora {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String rendered() {
            return FORMAT.format(value);
        }
    }

    /**
     * A link: {@code https:} under an allowed prefix, with no user info and no query, so a token can
     * only ever sit in the path (or a fragment). Never allowed in a subject.
     */
    record Link(URI value) implements TemplateValue {
        public Link {
            Objects.requireNonNull(value, "value");
            String text = value.toString();
            if (value.getRawUserInfo() != null || value.getRawQuery() != null || !isAllowed(text)) {
                throw new IllegalArgumentException("an e-mail link must be an allowed https path without a query");
            }
        }

        @Override
        public String rendered() {
            return value.toString();
        }

        /** Never prints the link, which can carry a token. */
        @Override
        public String toString() {
            return "Link[redacted]";
        }
    }

    /** True when {@code url} starts with an allowed prefix. */
    static boolean isAllowed(String url) {
        return ALLOWED_LINK_PREFIXES.stream().anyMatch(url::startsWith);
    }
}

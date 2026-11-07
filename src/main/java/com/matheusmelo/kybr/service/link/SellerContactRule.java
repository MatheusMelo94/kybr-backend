package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.model.workspace.ContatoVendedor;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The publishing rule for a workspace's seller contact: a plain e-mail address that can safely
 * become a {@code mailto:} (the frontend's strict pattern, at most 254 characters), or an absolute
 * {@code https:} URL with a host and no userinfo, in printable ASCII. Pure and stateless. Checked at
 * provisioning and again, defensively, before a contact is published.
 */
public final class SellerContactRule {

    public static final int MAX_EMAIL_LENGTH = 254;

    private static final Pattern STRICT_EMAIL = Pattern.compile(
            "^[A-Za-z0-9._+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");
    private static final Pattern PRINTABLE_ASCII_NO_BACKSLASH = Pattern.compile("^[\\x21-\\x5B\\x5D-\\x7E]+$");

    private SellerContactRule() {
    }

    /**
     * The reason a contact can't be published ({@code missing}, {@code email_length},
     * {@code email_format}, {@code url_format}), or empty when it can. The reason never quotes the
     * value.
     */
    public static Optional<String> violation(ContatoVendedor contato) {
        if (contato == null || contato.tipo() == null || contato.valor() == null) {
            return Optional.of("missing");
        }
        return switch (contato.tipo()) {
            case email -> emailViolation(contato.valor());
            case url -> urlViolation(contato.valor());
        };
    }

    private static Optional<String> emailViolation(String value) {
        if (value.length() > MAX_EMAIL_LENGTH) {
            return Optional.of("email_length");
        }
        return STRICT_EMAIL.matcher(value).matches() ? Optional.empty() : Optional.of("email_format");
    }

    private static Optional<String> urlViolation(String value) {
        if (!PRINTABLE_ASCII_NO_BACKSLASH.matcher(value).matches()) {
            return Optional.of("url_format");
        }
        try {
            URI uri = new URI(value);
            boolean valid = "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
            return valid ? Optional.empty() : Optional.of("url_format");
        } catch (URISyntaxException e) {
            return Optional.of("url_format");
        }
    }
}

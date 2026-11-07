package com.matheusmelo.kybr.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Regularisation links, pure and stateless: only an absolute {@code https:} URL on {@code gov.br}
 * or one of its subdomains, without user info, is ever shown to a seller.
 */
public final class GovBrUrl {

    private GovBrUrl() {
    }

    public static boolean isGovBr(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("gov.br") || host.endsWith(".gov.br");
    }
}

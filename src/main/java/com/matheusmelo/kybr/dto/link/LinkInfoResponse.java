package com.matheusmelo.kybr.dto.link;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * E01: the link's public info.
 *
 * @param nome the workspace's name, shown as text
 * @param contatoVendedor how the seller reaches the workspace
 */
public record LinkInfoResponse(
        @Schema(description = "The workspace's name, shown as text.", minLength = 1,
                requiredMode = Schema.RequiredMode.REQUIRED) String nome,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ContatoVendedor contatoVendedor) {

    /** The contact's kind. The constants are the wire values. */
    public enum Tipo {
        email,
        url
    }

    /**
     * @param tipo {@code email} or {@code url}
     * @param valor a plain e-mail address that can safely become a {@code mailto:}, or an absolute
     *     {@code https:} URL without userinfo
     */
    @Schema(description = "How the seller reaches the workspace.")
    public record ContatoVendedor(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Tipo tipo,
            @Schema(description = "With `email`, a plain address (`[A-Za-z0-9._+-]` local part, a dotted "
                    + "domain, at most 254 characters) that can safely become a `mailto:`; with `url`, an "
                    + "absolute `https:` URL without userinfo.", maxLength = 254,
                    requiredMode = Schema.RequiredMode.REQUIRED) String valor) {
    }
}

package com.matheusmelo.kybr.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** One workspace as the operator reads it: its id and slug, and nothing else internal. */
public record AdminWorkspaceResponse(
        UUID id,
        String slug,
        String nome,
        String categoria,
        String plano,
        @JsonProperty("link_ativo") boolean linkAtivo,
        @JsonProperty("autofill_ativo") boolean autofillAtivo) {
}

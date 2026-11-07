package com.matheusmelo.kybr.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The process-wide switches, as this machine currently serves them. */
public record AdminSwitchesResponse(
        @JsonProperty("autofill_global") boolean autofillGlobal,
        @JsonProperty("gasto_provedor") boolean gastoProvedor) {
}

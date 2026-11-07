package com.matheusmelo.kybr.dto.link;

import com.matheusmelo.kybr.dto.validation.CnpjCanonico;
import com.matheusmelo.kybr.dto.validation.PrintableAscii;
import com.matheusmelo.kybr.dto.validation.Responsavel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * E03: start a verification. Fields are declared in the order their errors are reported.
 *
 * @param cnpj the company's CNPJ, canonical (14 characters, no punctuation)
 * @param responsavel the person responsible for the company, as the seller typed it
 * @param ref the integrator's reference, optional; stored untouched and never echoed
 * @param extId the integrator's external id, optional; never verified, stored untouched and never
 *     echoed
 */
public record VerifyRequest(
        @Schema(description = "The company's CNPJ, canonical: 14 upper-case characters, no punctuation, valid "
                + "check digits. Alphanumeric CNPJs are accepted.", pattern = "^[0-9A-Z]{12}[0-9]{2}$",
                example = "12ABC34501DE35")
        @NotNull(message = "CNPJ_INVALIDO") @CnpjCanonico String cnpj,
        @Schema(description = "The person responsible for the company, as the seller typed it: at least two "
                + "words, at most 120 characters after NFC normalisation, letters, space, `'`, `-` and `.` only. "
                + "Stored NFC-normalised and trimmed.", maxLength = 120, example = "Maria Exemplo")
        @NotNull(message = "RESPONSAVEL_INVALIDO") @Responsavel String responsavel,
        @Schema(description = "The integrator's reference, optional: printable ASCII, at most 256 characters. "
                + "Stored untouched and never echoed.", maxLength = 256)
        @PrintableAscii(max = 256) String ref,
        @Schema(description = "The integrator's external id, optional: printable ASCII, at most 256 characters. "
                + "It is not verified: anyone can edit the link's query string. Stored untouched, shown as "
                + "unverified and never echoed. Match a case on the CNPJ plus your own record, never on `ext_id` "
                + "alone.", maxLength = 256)
        @PrintableAscii(max = 256) String extId) {

    @Override
    public String toString() {
        return "VerifyRequest[redacted]";
    }
}

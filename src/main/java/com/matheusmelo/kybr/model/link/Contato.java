package com.matheusmelo.kybr.model.link;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The seller's contact e-mail for a case.
 *
 * @param emailCifrado the address, AEAD-encrypted under the personal-data keyset (AAD = case id)
 * @param emailMascarado the only readable form: first character, {@code •••}, {@code @}, domain
 *
 * <p>Equality compares the ciphertext's bytes; {@code toString} never prints it.
 */
public record Contato(
        @Field("email_cifrado") byte[] emailCifrado,
        @Field("email_mascarado") String emailMascarado,
        @Field("alterado_em") Instant alteradoEm) {

    @Override
    public boolean equals(Object other) {
        return other instanceof Contato that && Arrays.equals(emailCifrado, that.emailCifrado)
                && Objects.equals(emailMascarado, that.emailMascarado)
                && Objects.equals(alteradoEm, that.alteradoEm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(emailCifrado), emailMascarado, alteradoEm);
    }

    @Override
    public String toString() {
        return "Contato[emailMascarado=" + emailMascarado + ", alteradoEm=" + alteradoEm + "]";
    }
}

package com.matheusmelo.kybr.model.link;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * An audit event's details: a closed set of fields, never a free-form map. Every personal value is
 * stored masked only; a value that isn't masked is refused.
 *
 * @param cnpjMascarado the case's CNPJ, masked
 * @param anteriorMascarado the previous contact e-mail, masked
 * @param novoMascarado the new contact e-mail, masked
 * @param slug the workspace slug an operator acted on (not personal data)
 * @param slugExplicito an operator chose the slug instead of it being assigned
 * @param ativo the value an operator set a switch to
 * @param documentos how many stored documents a system job deleted (a count, never a key)
 * @param contatoRemovido whether a system job removed a contact e-mail
 * @param documento an upload's type, file hashes and sizes, or the scanner's signature name
 */
public record ResumoAuditoria(
        @Field("cnpj_mascarado") String cnpjMascarado,
        @Field("anterior_mascarado") String anteriorMascarado,
        @Field("novo_mascarado") String novoMascarado,
        @Field("slug") String slug,
        @Field("slug_explicito") Boolean slugExplicito,
        @Field("ativo") Boolean ativo,
        @Field("documentos") Integer documentos,
        @Field("contato_removido") Boolean contatoRemovido,
        @Field("documento") ResumoDocumento documento) {

    /** Five or more digits in a row: a CNPJ or CPF fragment long enough to identify someone. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{5,}");
    /** An e-mail address's local part. */
    private static final Pattern LOCAL_PART = Pattern.compile("([^\\s@]+)@");

    public ResumoAuditoria {
        requireMasked("cnpj_mascarado", cnpjMascarado);
        requireMasked("anterior_mascarado", anteriorMascarado);
        requireMasked("novo_mascarado", novoMascarado);
    }

    /** Details without the system-job counts or an upload summary. */
    public ResumoAuditoria(String cnpjMascarado, String anteriorMascarado, String novoMascarado, String slug,
            Boolean slugExplicito, Boolean ativo) {
        this(cnpjMascarado, anteriorMascarado, novoMascarado, slug, slugExplicito, ativo, null, null, null);
    }

    /** A retention run: how many documents were deleted and whether a contact was removed. */
    public static ResumoAuditoria ofRetencao(int documentos, boolean contatoRemovido) {
        return new ResumoAuditoria(null, null, null, null, null, null, documentos, contatoRemovido, null);
    }

    /** An upload: its type, file hashes and sizes, or the scanner's signature name. */
    public static ResumoAuditoria ofDocumento(ResumoDocumento documento) {
        return new ResumoAuditoria(null, null, null, null, null, null, null, null, documento);
    }

    public static ResumoAuditoria ofCnpj(String cnpjMascarado) {
        return new ResumoAuditoria(cnpjMascarado, null, null, null, null, null);
    }

    public static ResumoAuditoria ofContato(String anteriorMascarado, String novoMascarado) {
        return new ResumoAuditoria(null, anteriorMascarado, novoMascarado, null, null, null);
    }

    private static void requireMasked(String field, String value) {
        if (value == null) {
            return;
        }
        boolean masked = (value.indexOf('•') >= 0 || value.indexOf('*') >= 0)
                && !DIGIT_RUN.matcher(value).find() && !showsEmailLocalPart(value);
        if (!masked) {
            throw new IllegalArgumentException(field + " must be masked");
        }
    }

    /** True when an address shows more than the first character of its local part. */
    private static boolean showsEmailLocalPart(String value) {
        Matcher local = LOCAL_PART.matcher(value);
        while (local.find()) {
            if (local.group(1).chars().filter(c -> c != '•' && c != '*').count() > 1) {
                return true;
            }
        }
        return false;
    }
}

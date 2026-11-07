package com.matheusmelo.kybr.model.workspace;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Process-wide kill switches, in one document ({@value #GLOBAL_ID}), set by an operator.
 *
 * @param autofillAtivo off: every company lookup answers "autofill disabled"
 * @param gastoProvedorAtivo off: no provider spend at all (lookups cache-only, verifications deferred)
 */
@Document(RuntimeFlags.COLLECTION)
public record RuntimeFlags(
        @Id String id,
        @Field("autofill_ativo") boolean autofillAtivo,
        @Field("gasto_provedor_ativo") boolean gastoProvedorAtivo) {

    public static final String COLLECTION = "runtime_flags";
    public static final String GLOBAL_ID = "global";

    /** The flags when no operator has set any: everything on. */
    public static RuntimeFlags defaults() {
        return new RuntimeFlags(GLOBAL_ID, true, true);
    }
}

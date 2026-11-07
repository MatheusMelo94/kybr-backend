package com.matheusmelo.kybr.model.link;

import java.time.YearMonth;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Provider credits spent in one month (São Paulo time), updated only by atomic increments.
 *
 * @param id the month, {@code YYYY-MM}
 * @param usados every credit spent
 * @param d2 credits spent on company lookups
 * @param d3 credits spent on verifications
 * @param base credits for base records
 * @param simples credits for the Simples/MEI add-on
 * @param ie credits for the state registration add-on
 */
@Document(CreditLedger.COLLECTION)
public record CreditLedger(
        @Id String id,
        @Field("usados") long usados,
        @Field("d2") long d2,
        @Field("d3") long d3,
        @Field("base") long base,
        @Field("simples") long simples,
        @Field("ie") long ie) {

    public static final String COLLECTION = "credit_ledger";
    public static final String USADOS = "usados";
    public static final String D2 = "d2";
    public static final String D3 = "d3";
    public static final String BASE = "base";
    public static final String SIMPLES = "simples";
    public static final String IE = "ie";

    /** The ledger id for {@code month}: {@code YYYY-MM}. */
    public static String idFor(YearMonth month) {
        return month.toString();
    }
}

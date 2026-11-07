package com.matheusmelo.kybr.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.matheusmelo.kybr.model.RateLimitCounter;
import com.matheusmelo.kybr.model.link.CreditLedger;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Atomic provider-spend bookkeeping: the monthly credit ledger and the daily budget counters. A
 * custom component is justified by the layer rules: a derived query can't express an upsert with
 * {@code $inc} of arbitrary amounts in one round trip, and concurrent bookings must never lose an
 * increment.
 *
 * <p>Daily counters live with the rate-limit counters (same shape: an id holding a name, a hashed
 * subject and the window start, plus a TTL expiry), but are incremented by an amount.
 */
@Component
public class CreditLedgerOps {

    private static final FindAndModifyOptions UPSERT_RETURN_NEW = FindAndModifyOptions.options()
            .upsert(true)
            .returnNew(true);

    /** What a booking was spent on. */
    public enum Use {
        D2(CreditLedger.D2),
        D3(CreditLedger.D3);

        private final String field;

        Use(String field) {
            this.field = field;
        }
    }

    private final MongoTemplate mongoTemplate;

    public CreditLedgerOps(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Adds the credits to month {@code monthId}, creating it if absent, and returns the new totals. */
    public CreditLedger book(String monthId, Use use, long base, long simples, long ie) {
        long total = base + simples + ie;
        // Every field is incremented (the other use by 0), so a new month document has them all.
        Update increment = new Update()
                .inc(CreditLedger.USADOS, total)
                .inc(Use.D2.field, use == Use.D2 ? total : 0)
                .inc(Use.D3.field, use == Use.D3 ? total : 0)
                .inc(CreditLedger.BASE, base)
                .inc(CreditLedger.SIMPLES, simples)
                .inc(CreditLedger.IE, ie);
        CreditLedger ledger = mongoTemplate.findAndModify(Query.query(where("_id").is(monthId)), increment,
                UPSERT_RETURN_NEW, CreditLedger.class);
        if (ledger == null) {
            throw new IllegalStateException("upsert returned no document");
        }
        return ledger;
    }

    public Optional<CreditLedger> month(String monthId) {
        return Optional.ofNullable(mongoTemplate.findById(monthId, CreditLedger.class));
    }

    /** Adds {@code amount} to counter {@code id}, creating it with {@code expiresAt}, and returns the new count. */
    public long addToCounter(String id, long amount, Instant expiresAt) {
        Update increment = new Update().inc(RateLimitCounter.COUNT, amount)
                .setOnInsert(RateLimitCounter.EXPIRES_AT, expiresAt);
        RateLimitCounter counter = mongoTemplate.findAndModify(Query.query(where("_id").is(id)), increment,
                UPSERT_RETURN_NEW, RateLimitCounter.class);
        if (counter == null) {
            throw new IllegalStateException("upsert returned no document");
        }
        return counter.count();
    }

    /** The counter's value; 0 when it doesn't exist (yet, or any more). */
    public long counter(String id) {
        RateLimitCounter counter = mongoTemplate.findById(id, RateLimitCounter.class);
        return counter == null ? 0 : counter.count();
    }
}

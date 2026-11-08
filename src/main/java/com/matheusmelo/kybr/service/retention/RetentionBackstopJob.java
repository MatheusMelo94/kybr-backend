package com.matheusmelo.kybr.service.retention;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.repository.RetentionClaims;
import com.matheusmelo.kybr.service.jobs.BackgroundJob;
import com.matheusmelo.kybr.service.jobs.JobName;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The retention backstop ({@code kybr.retention.backstop-interval}, daily): a case never decided
 * would otherwise keep its documents forever, so its retention date is the seller's last activity
 * plus {@code kybr.retention.backstop} (12 months), in UTC, a month end clamping to the target
 * month's last day. Activity is the latest of the case's creation, its contact change and its
 * latest stored document.
 *
 * <p>The date is recomputed on every run while it came from the backstop, so later activity moves
 * it forward; a date set by a decision is never touched, nor is a case already claimed or purged by
 * the retention job (which deletes the case's documents once the date passes).
 */
@Component
public class RetentionBackstopJob implements BackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionBackstopJob.class);

    private final RetentionClaims claims;
    private final Period backstop;

    @Autowired
    public RetentionBackstopJob(RetentionClaims claims, KybrProperties properties) {
        this(claims, properties.retention().backstop());
    }

    RetentionBackstopJob(RetentionClaims claims, Period backstop) {
        this.claims = claims;
        this.backstop = backstop;
    }

    @Override
    public JobName name() {
        return JobName.BACKSTOP;
    }

    @Override
    public void runOnce() {
        int moved = 0;
        try (Stream<LinkCase> candidates = claims.backstopCandidates()) {
            for (LinkCase linkCase : (Iterable<LinkCase>) candidates::iterator) {
                Instant target = lastActivity(linkCase).atOffset(ZoneOffset.UTC).plus(backstop).toInstant();
                if (!target.equals(linkCase.retencaoAte()) && claims.setBackstop(linkCase.id(), target)) {
                    moved++;
                }
            }
        }
        log.info("event=retention_backstop atualizados={}", moved);
    }

    private Instant lastActivity(LinkCase linkCase) {
        Instant latest = linkCase.criadoEm();
        if (linkCase.contato() != null && linkCase.contato().alteradoEm() != null
                && linkCase.contato().alteradoEm().isAfter(latest)) {
            latest = linkCase.contato().alteradoEm();
        }
        Instant upload = claims.latestDocumentAt(linkCase.id()).orElse(latest);
        return upload.isAfter(latest) ? upload : latest;
    }
}

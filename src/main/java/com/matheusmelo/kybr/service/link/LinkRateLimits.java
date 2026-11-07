package com.matheusmelo.kybr.service.link;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.LimitWindow;
import com.matheusmelo.kybr.config.KybrProperties.LinkLimits;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.MetricTags;
import com.matheusmelo.kybr.service.ratelimit.RateLimitPolicy;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.service.ratelimit.SubjectKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The seller link's request limits, declared from {@code kybr.link.limits}. Each check picks the
 * per-IP policy for a per-client IP, or the {@code -unknown} policy counted per link for a shared
 * one (the typed subjects refuse the wrong pairing), and throws 429 with {@code Retry-After} on
 * denial. Callers check before any write or token lookup.
 */
@Service
public class LinkRateLimits {

    /** Verification starts per link: {@code accepted} here; the idempotency interceptor adds replays and refusals. */
    public static final String VERIFY_METRIC = "kybr.link.verify";

    private final RateLimiter limiter;
    private final MeterRegistry meters;
    private final RateLimitPolicy lookupIp;
    private final RateLimitPolicy lookupIpDaily;
    private final RateLimitPolicy lookupIpUnknown;
    private final RateLimitPolicy verifyIp;
    private final RateLimitPolicy verifyIpUnknown;
    private final RateLimitPolicy verifyCnpj;
    private final RateLimitPolicy caseIp;
    private final RateLimitPolicy caseIpUnknown;
    private final RateLimitPolicy contactIp;
    private final RateLimitPolicy contactIpUnknown;
    private final RateLimitPolicy contactCase;

    @Autowired
    public LinkRateLimits(RateLimiter limiter, KybrProperties properties, MeterRegistry meters) {
        this(limiter, properties.link().limits(), meters);
    }

    public LinkRateLimits(RateLimiter limiter, LinkLimits limits, MeterRegistry meters) {
        this.limiter = limiter;
        this.meters = meters;
        lookupIp = policy("link.lookup.ip", limits.lookupIp(), ErrorCode.LIMITE_CONSULTAS, SubjectKind.IP);
        lookupIpDaily = policy("link.lookup.ip-daily", limits.lookupIpDaily(), ErrorCode.LIMITE_CONSULTAS,
                SubjectKind.IP);
        lookupIpUnknown = policy("link.lookup.ip-unknown", limits.lookupIpUnknown(), ErrorCode.LIMITE_CONSULTAS,
                SubjectKind.IP_UNKNOWN);
        verifyIp = policy("link.verify.ip", limits.verifyIp(), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP);
        verifyIpUnknown = policy("link.verify.ip-unknown", limits.verifyIpUnknown(), ErrorCode.LIMITE_TENTATIVAS,
                SubjectKind.IP_UNKNOWN);
        verifyCnpj = policy("link.verify.cnpj", limits.verifyCnpj(), ErrorCode.LIMITE_CNPJ, SubjectKind.CNPJ);
        caseIp = policy("link.case.ip", limits.caseIp(), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP);
        caseIpUnknown = policy("link.case.ip-unknown", limits.caseIpUnknown(), ErrorCode.LIMITE_TENTATIVAS,
                SubjectKind.IP_UNKNOWN);
        contactIp = policy("link.contact.ip", limits.contactIp(), ErrorCode.LIMITE_TENTATIVAS, SubjectKind.IP);
        contactIpUnknown = policy("link.contact.ip-unknown", limits.contactIpUnknown(), ErrorCode.LIMITE_TENTATIVAS,
                SubjectKind.IP_UNKNOWN);
        contactCase = policy("link.contact.case", limits.contactCase(), ErrorCode.LIMITE_TENTATIVAS,
                SubjectKind.CASE);
    }

    /** Company lookup: per IP per minute and per day ({@code LIMITE_CONSULTAS}); shared IPs per link. */
    public void lookup(ClientIp clientIp, Workspace workspace) {
        RateLimitSubject subject = RateLimitSubject.forClient(clientIp, workspace.slug());
        if (subject instanceof RateLimitSubject.IpUnknown) {
            limiter.consumeOrThrow(lookupIpUnknown, subject);
            return;
        }
        limiter.consumeOrThrow(lookupIp, subject);
        limiter.consumeOrThrow(lookupIpDaily, subject);
    }

    /**
     * Verification start: per (IP, link) or, for a shared IP, per link ({@code LIMITE_TENTATIVAS});
     * then per (link, CNPJ) ({@code LIMITE_CNPJ}). An accepted call moves the per-link volume
     * counter, which alerts and never denies.
     */
    public void verify(ClientIp clientIp, Workspace workspace, String cnpj) {
        RateLimitSubject subject = RateLimitSubject.forClientInScope(clientIp, workspace.slug());
        limiter.consumeOrThrow(subject instanceof RateLimitSubject.IpUnknown ? verifyIpUnknown : verifyIp, subject);
        limiter.consumeOrThrow(verifyCnpj, new RateLimitSubject.SlugCnpj(workspace.slug(), cnpj));
        meters.counter(VERIFY_METRIC, MetricTags.SLUG, MetricTags.slug(workspace), "resultado", "accepted")
                .increment();
    }

    /** Case read: per IP, or per link for a shared IP ({@code LIMITE_TENTATIVAS}). */
    public void caseRead(ClientIp clientIp, Workspace workspace) {
        perIpOrLink(clientIp, workspace, caseIp, caseIpUnknown);
    }

    /** Contact change request: per IP, or per link for a shared IP ({@code LIMITE_TENTATIVAS}). */
    public void contact(ClientIp clientIp, Workspace workspace) {
        perIpOrLink(clientIp, workspace, contactIp, contactIpUnknown);
    }

    /** An effective contact address change on a case ({@code LIMITE_TENTATIVAS}). */
    public void contactChange(UUID caseId) {
        limiter.consumeOrThrow(contactCase, new RateLimitSubject.CaseId(caseId));
    }

    private void perIpOrLink(ClientIp clientIp, Workspace workspace, RateLimitPolicy perIp,
            RateLimitPolicy perLinkWhenUnknown) {
        RateLimitSubject subject = RateLimitSubject.forClient(clientIp, workspace.slug());
        limiter.consumeOrThrow(subject instanceof RateLimitSubject.IpUnknown ? perLinkWhenUnknown : perIp, subject);
    }

    private static RateLimitPolicy policy(String name, LimitWindow window, ErrorCode code, SubjectKind kind) {
        return new RateLimitPolicy(name, window.limit(), window.window(), code, kind);
    }
}

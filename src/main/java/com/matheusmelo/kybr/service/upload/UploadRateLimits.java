package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.LimitWindow;
import com.matheusmelo.kybr.config.KybrProperties.UploadLimits;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.ClientIp;
import com.matheusmelo.kybr.service.ratelimit.RateLimitPolicy;
import com.matheusmelo.kybr.service.ratelimit.RateLimitSubject;
import com.matheusmelo.kybr.service.ratelimit.RateLimiter;
import com.matheusmelo.kybr.service.ratelimit.SubjectKind;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The document upload's request limits, from {@code kybr.upload.limits}: per socket IP (generous,
 * so shared mobile addresses still work), or per link when the platform gave no client address,
 * then per case per day. A denial is 429 {@code LIMITE_TENTATIVAS} with {@code Retry-After}; the
 * per-IP check runs before any database read of the request.
 */
@Service
public class UploadRateLimits {

    private final RateLimiter limiter;
    private final RateLimitPolicy ip;
    private final RateLimitPolicy ipUnknown;
    private final RateLimitPolicy perCase;

    @Autowired
    public UploadRateLimits(RateLimiter limiter, KybrProperties properties) {
        this(limiter, properties.upload().limits());
    }

    public UploadRateLimits(RateLimiter limiter, UploadLimits limits) {
        this.limiter = limiter;
        ip = policy("upload.ip", limits.ip(), SubjectKind.IP);
        ipUnknown = policy("upload.ip-unknown", limits.ipUnknown(), SubjectKind.IP_UNKNOWN);
        perCase = policy("upload.case", limits.perCase(), SubjectKind.CASE);
    }

    /**
     * Per socket IP, or per link for an unknown address. Takes the slug as sent, so it runs before
     * the link is looked up.
     */
    public void request(ClientIp clientIp, String slug) {
        RateLimitSubject subject = RateLimitSubject.forClient(clientIp, slug);
        limiter.consumeOrThrow(subject instanceof RateLimitSubject.IpUnknown ? ipUnknown : ip, subject);
    }

    /** Per case per day. */
    public void perCase(UUID caseId) {
        limiter.consumeOrThrow(perCase, new RateLimitSubject.CaseId(caseId));
    }

    private static RateLimitPolicy policy(String name, LimitWindow window, SubjectKind kind) {
        return new RateLimitPolicy(name, window.limit(), window.window(), ErrorCode.LIMITE_TENTATIVAS, kind);
    }
}

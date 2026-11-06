package com.matheusmelo.kybr.config.filter;

/**
 * Every servlet filter order, in one place. The order is part of the design.
 *
 * <p>All of them run before Spring Security's filter chain (order -100). Filters [1] to [3] are
 * outermost, so every response, including one written by a later filter or by Spring Security,
 * carries {@code X-Request-Id}, passes the response guard and is access-logged. Those three are
 * also registered for the {@code ERROR} dispatch, so error responses carry the request id and the
 * security headers too; see {@link #runsOnErrorDispatch}.
 */
public final class FilterOrder {

    /** [1] Generates {@code X-Request-Id} and MDC {@code request_id}. */
    public static final int REQUEST_ID = -1000;
    /** [2] No redirects, no cookies, no sessions; sets the security headers. */
    public static final int RESPONSE_GUARD = -900;
    /** [3] One structured line per request. */
    public static final int ACCESS_LOG = -800;
    /** [4] Rejects non-normalised URIs and caches the parsed {@code RequestPath} for later filters. */
    public static final int PATH_FIREWALL = -700;
    /** [5] Request body size limits. */
    public static final int BODY_SIZE_LIMIT = -600;
    /** [6] The BFF key, deny by default. */
    public static final int BFF_KEY = -500;
    /** [7] Client-IP resolution (needs [6]'s verdict). */
    public static final int CLIENT_IP = -400;
    /** [8] Buffers POST/PUT JSON bodies for idempotency hashing. */
    public static final int CACHED_BODY = -300;

    private FilterOrder() {
    }

    /** Whether the filter at this order is also registered for the {@code ERROR} dispatch. */
    public static boolean runsOnErrorDispatch(int order) {
        return order <= ACCESS_LOG;
    }
}

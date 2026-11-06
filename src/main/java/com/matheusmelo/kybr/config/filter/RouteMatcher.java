package com.matheusmelo.kybr.config.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * A (method, path pattern) rule matched exactly against the normalised path cached by
 * {@link PathFirewallFilter}, never the raw URI, so an encoded or case variant can't dodge a
 * match. No optional trailing slash.
 */
record RouteMatcher(HttpMethod method, PathPattern pattern) {

    static RouteMatcher of(HttpMethod method, String pattern) {
        return new RouteMatcher(method, PathPatternParser.defaultInstance.parse(pattern));
    }

    boolean matches(HttpServletRequest request) {
        return method.matches(request.getMethod())
                && pattern.matches(PathFirewallFilter.requestPath(request).pathWithinApplication());
    }
}

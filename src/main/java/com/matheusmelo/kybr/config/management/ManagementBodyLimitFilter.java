package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.KybrProperties.Limits;
import com.matheusmelo.kybr.config.filter.BodySizeLimitFilter;
import com.matheusmelo.kybr.config.filter.RequestIdFilter;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The management server's body cap ({@code kybr.admin.max-body}, 16 KiB): the public port's body
 * limit with one default and no routes. A declared length over the cap is 413 before the chain;
 * a body without one is counted as it's read, and passing the cap is the same 413 (Actuator
 * would otherwise turn the failure into a 500 or 400).
 */
public class ManagementBodyLimitFilter extends OncePerRequestFilter {

    private final BodySizeLimitFilter limit;
    private final ErrorResponseWriter writer;

    public ManagementBodyLimitFilter(DataSize maxBody, ErrorResponseWriter writer) {
        this.limit = new BodySizeLimitFilter(new Limits(maxBody, List.of(), List.of()), writer);
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limit.doFilter(request, response, chain);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!tooLarge(e) || response.isCommitted()) {
                throw e;
            }
            response.reset();
            if (request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String id) {
                response.setHeader(RequestIdFilter.HEADER, id);
            }
            writer.write(response, ErrorCode.REQUISICAO_GRANDE);
        }
    }

    private static boolean tooLarge(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }
}

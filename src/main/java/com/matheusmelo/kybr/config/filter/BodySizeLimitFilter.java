package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.KybrProperties.Limits;
import com.matheusmelo.kybr.config.KybrProperties.RouteLimit;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistration;
import org.springframework.core.Ordered;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Filter [5]: request body limits.
 *
 * <ul>
 *   <li>The limit is the route's override ({@code kybr.limits.routes}) or the 1 MiB default.
 *   <li>A {@code Content-Length} over the limit: 413 {@code REQUISICAO_GRANDE} before the chain.
 *   <li>A body without a length (chunked) is counted as it is read; passing the limit throws
 *       {@link PayloadTooLargeException}, rendered as the same 413.
 *   <li>Multipart routes ({@code kybr.limits.multipart}, e.g. the seller document upload,
 *       {@code POST /public/v/{slug}/cases/{token}/documents}) accept only
 *       {@code multipart/form-data}: any other or missing content type is 415
 *       {@code MIDIA_NAO_SUPORTADA} before the body is read. Their own cap applies like any other.
 *       Anywhere else multipart is 413 when over the limit, otherwise 415, and the handler never
 *       runs.
 *   <li>No route is uncapped: every request reaching the chain carries the counting
 *       stream, and {@link CachedBodyFilter} refuses to buffer a body without it.
 * </ul>
 *
 * Routes match on {@link PathFirewallFilter}'s normalised path.
 */
@Component
@FilterRegistration(order = FilterOrder.BODY_SIZE_LIMIT)
public class BodySizeLimitFilter extends OncePerRequestFilter implements Ordered {

    private record Limit(RouteMatcher route, long maxBytes) {
    }

    private final long defaultMaxBytes;
    private final List<Limit> routeLimits;
    private final List<Limit> multipartLimits;
    private final ErrorResponseWriter writer;

    @Autowired
    public BodySizeLimitFilter(KybrProperties properties, ErrorResponseWriter writer) {
        this(properties.limits(), writer);
    }

    public BodySizeLimitFilter(Limits limits, ErrorResponseWriter writer) {
        this.defaultMaxBytes = limits.defaultMaxBody().toBytes();
        this.routeLimits = limits(limits.routes());
        this.multipartLimits = limits(limits.multipart());
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Limit> multipartRoute = matching(multipartLimits, request);
        if (multipartRoute.isPresent() && !isMultipartFormData(request)) {
            writer.write(response, ErrorCode.MIDIA_NAO_SUPORTADA);
            return;
        }
        long limit = multipartRoute.or(() -> matching(routeLimits, request))
                .map(Limit::maxBytes).orElse(defaultMaxBytes);
        if (request.getContentLengthLong() > limit) {
            writer.write(response, ErrorCode.REQUISICAO_GRANDE);
            return;
        }
        if (multipartRoute.isEmpty() && isMultipart(request)) {
            writer.write(response, ErrorCode.MIDIA_NAO_SUPORTADA);
            return;
        }
        chain.doFilter(limited(request, limit), response);
    }

    /** {@code request} with its body counted against {@code maxBytes} as it is read. */
    static HttpServletRequest limited(HttpServletRequest request, long maxBytes) {
        return new LimitedRequest(request, maxBytes);
    }

    /** Whether filter [5]'s counting stream wraps this request (anywhere in the wrapper chain). */
    static boolean isLimited(HttpServletRequest request) {
        return WebUtils.getNativeRequest(request, LimitedRequest.class) != null;
    }

    private static List<Limit> limits(List<RouteLimit> routes) {
        return routes.stream()
                .map(r -> new Limit(RouteMatcher.of(r.method(), r.pattern()), r.maxBody().toBytes()))
                .toList();
    }

    private static Optional<Limit> matching(List<Limit> limits, HttpServletRequest request) {
        return limits.stream().filter(l -> l.route().matches(request)).findFirst();
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static boolean isMultipartFormData(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return FilterOrder.BODY_SIZE_LIMIT;
    }

    /** Counts body bytes as they are read and fails once the limit is passed. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private int counted(int bytes) {
            if (bytes > 0) {
                count += bytes;
                if (count > limit) {
                    throw new PayloadTooLargeException();
                }
            }
            return bytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return counted(delegate.read(buffer, offset, length));
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

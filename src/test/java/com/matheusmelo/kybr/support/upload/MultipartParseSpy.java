package com.matheusmelo.kybr.support.upload;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Counts every call on a request that makes the container parse a multipart body: the parts
 * themselves and the parameter accessors (which parse multipart bodies too). Installed as the
 * outermost filter, so whatever runs after it (filters, the multipart resolver, handlers) is
 * counted. Never scanned; imported by {@link UploadWebIntegrationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MultipartParseSpy {

    private static final AtomicInteger PARSE_CALLS = new AtomicInteger();

    /** Calls that would parse a multipart body since the last {@link #reset()}. */
    public static int parseCalls() {
        return PARSE_CALLS.get();
    }

    public static void reset() {
        PARSE_CALLS.set(0);
    }

    @Bean
    FilterRegistrationBean<Filter> multipartParseSpyFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(MultipartParseSpy::wrap);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/public/*");
        return registration;
    }

    private static void wrap(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(new Counting((HttpServletRequest) request), response);
    }

    private static final class Counting extends HttpServletRequestWrapper {

        Counting(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            PARSE_CALLS.incrementAndGet();
            return super.getParts();
        }

        @Override
        public Part getPart(String name) throws IOException, ServletException {
            PARSE_CALLS.incrementAndGet();
            return super.getPart(name);
        }

        @Override
        public String getParameter(String name) {
            PARSE_CALLS.incrementAndGet();
            return super.getParameter(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            PARSE_CALLS.incrementAndGet();
            return super.getParameterMap();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            PARSE_CALLS.incrementAndGet();
            return super.getParameterNames();
        }

        @Override
        public String[] getParameterValues(String name) {
            PARSE_CALLS.incrementAndGet();
            return super.getParameterValues(name);
        }
    }
}

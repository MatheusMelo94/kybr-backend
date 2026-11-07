package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.config.filter.IdempotencyInterceptor;
import com.matheusmelo.kybr.config.filter.LinkSlugInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring, in order: the idempotency interceptor (only {@code @Idempotent} handlers), then the
 * link slug interceptor (only {@code @LinkRoute} handlers), so a replay answers before the slug
 * is resolved.
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;
    private final LinkSlugInterceptor linkSlugInterceptor;

    public WebMvcConfig(IdempotencyInterceptor idempotencyInterceptor, LinkSlugInterceptor linkSlugInterceptor) {
        this.idempotencyInterceptor = idempotencyInterceptor;
        this.linkSlugInterceptor = linkSlugInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor);
        registry.addInterceptor(linkSlugInterceptor);
    }
}

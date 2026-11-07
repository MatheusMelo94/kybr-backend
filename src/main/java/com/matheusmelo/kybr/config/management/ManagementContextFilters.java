package com.matheusmelo.kybr.config.management;

import com.matheusmelo.kybr.config.KybrProperties;
import com.matheusmelo.kybr.config.filter.FilterOrder;
import com.matheusmelo.kybr.exception.ErrorResponseWriter;
import com.matheusmelo.kybr.util.LogRedactor;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * The servlet filters of the management server, which the public port's filters don't reach (the
 * management server is a separate child context). Imported into that context only, through
 * {@code META-INF/spring/…ManagementContextConfiguration.imports}; deliberately not a
 * {@code @Configuration}, so component scanning never registers these filters on the public port.
 *
 * <p>Order, outermost first, all before the security chain: request mark and id, access line,
 * body cap.
 */
public class ManagementContextFilters {

    @Bean
    FilterRegistrationBean<ManagementRequestFilter> managementRequestFilter() {
        FilterRegistrationBean<ManagementRequestFilter> registration =
                new FilterRegistrationBean<>(new ManagementRequestFilter());
        registration.setOrder(FilterOrder.REQUEST_ID);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ManagementAccessLogFilter> managementAccessLogFilter(LogRedactor redactor) {
        FilterRegistrationBean<ManagementAccessLogFilter> registration = new FilterRegistrationBean<>(
                new ManagementAccessLogFilter(ManagementSecurityConfig.openProbes(), redactor));
        registration.setOrder(FilterOrder.ACCESS_LOG);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ManagementBodyLimitFilter> managementBodyLimitFilter(KybrProperties properties,
            ErrorResponseWriter writer) {
        FilterRegistrationBean<ManagementBodyLimitFilter> registration = new FilterRegistrationBean<>(
                new ManagementBodyLimitFilter(properties.admin().maxBody(), writer));
        registration.setOrder(FilterOrder.BODY_SIZE_LIMIT);
        return registration;
    }
}

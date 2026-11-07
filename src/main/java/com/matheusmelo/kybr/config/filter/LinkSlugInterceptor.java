package com.matheusmelo.kybr.config.filter;

import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.service.link.LinkRoute;
import com.matheusmelo.kybr.service.link.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves the workspace of a {@link LinkRoute} handler from its {@code slug} path variable and
 * stores it in {@value #WORKSPACE_ATTRIBUTE}. Registered after the idempotency interceptor, so a
 * replay still answers first; and, as an interceptor, it runs before argument binding, so an
 * unknown slug is 404 {@code LINK_NAO_ENCONTRADO} even when the body is invalid. Handlers without
 * {@link LinkRoute} are untouched.
 */
@Component
public class LinkSlugInterceptor implements HandlerInterceptor {

    /** The request attribute holding the resolved {@link Workspace}. */
    public static final String WORKSPACE_ATTRIBUTE = "com.matheusmelo.kybr.config.filter.LinkSlugInterceptor.workspace";

    private final LinkService links;

    public LinkSlugInterceptor(LinkService links) {
        this.links = links;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LinkRoute route = handler instanceof HandlerMethod method ? method.getMethodAnnotation(LinkRoute.class) : null;
        if (route == null) {
            return true;
        }
        String slug = uriVariables(request).get("slug");
        Workspace workspace = route.readableWhenDisabled() ? links.resolveReadable(slug) : links.resolveActive(slug);
        request.setAttribute(WORKSPACE_ATTRIBUTE, workspace);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}

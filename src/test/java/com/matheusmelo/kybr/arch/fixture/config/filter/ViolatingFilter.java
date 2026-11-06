package com.matheusmelo.kybr.arch.fixture.config.filter;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Rule fixture, never run: every servlet call the B0 rules forbid. */
public class ViolatingFilter {

    void redirect(HttpServletResponse response) throws IOException {
        response.sendRedirect("/elsewhere");
    }

    void cookie(HttpServletResponse response) {
        response.addCookie(new Cookie("name", "value"));
    }

    Object session(HttpServletRequest request) {
        return request.getSession();
    }

    String forwardedFor(HttpServletRequest request) {
        return request.getHeader("x-forwarded-for");
    }

    boolean routesOnRawUri(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/v1/");
    }
}

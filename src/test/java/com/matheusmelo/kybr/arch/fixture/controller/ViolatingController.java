package com.matheusmelo.kybr.arch.fixture.controller;

import com.matheusmelo.kybr.arch.fixture.repository.FixtureRepository;
import com.matheusmelo.kybr.util.Cnpj;
import org.springframework.web.bind.annotation.CookieValue;

/** Rule fixture, never run: a controller that reads a repository, canonicalises a CNPJ and reads a cookie. */
public class ViolatingController {

    FixtureRepository repository;

    String cookie(@CookieValue("session") String session) {
        return session;
    }

    Object canonical(String value) {
        return Cnpj.canonicalize(value);
    }
}

package com.matheusmelo.kybr.arch.fixture.controller;

import com.matheusmelo.kybr.arch.fixture.repository.FixtureRepository;
import com.matheusmelo.kybr.util.Cnpj;

/** Rule fixture, never run: a controller that reads a repository and canonicalises a CNPJ. */
public class ViolatingController {

    FixtureRepository repository;

    Object canonical(String value) {
        return Cnpj.canonicalize(value);
    }
}

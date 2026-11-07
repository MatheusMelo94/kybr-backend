package com.matheusmelo.kybr.arch.fixture.service;

import com.matheusmelo.kybr.arch.fixture.dto.FixtureDto;

/** Rule fixture, never run: a service that returns an API DTO instead of leaving that to a mapper. */
public class DtoAwareService {

    FixtureDto find() {
        return new FixtureDto("x");
    }
}

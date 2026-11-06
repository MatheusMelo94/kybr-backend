package com.matheusmelo.kybr.arch.fixture.model;

import com.matheusmelo.kybr.arch.fixture.dto.FixtureDto;

/** Rule fixture: a model that depends on a DTO. */
public record ViolatingModel(FixtureDto dto) {
}

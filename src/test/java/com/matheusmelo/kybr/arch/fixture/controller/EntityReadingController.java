package com.matheusmelo.kybr.arch.fixture.controller;

import com.matheusmelo.kybr.arch.fixture.dto.FixtureDto;
import com.matheusmelo.kybr.arch.fixture.model.FixtureEntity;

/** Rule fixture, never run: a controller that builds its response from an entity without a mapper. */
public class EntityReadingController {

    FixtureDto respond(FixtureEntity entity) {
        return new FixtureDto(entity.name());
    }
}

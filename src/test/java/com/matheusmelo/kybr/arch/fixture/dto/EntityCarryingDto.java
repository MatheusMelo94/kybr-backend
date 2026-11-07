package com.matheusmelo.kybr.arch.fixture.dto;

import com.matheusmelo.kybr.arch.fixture.model.FixtureEntity;

/** Rule fixture, never run: a DTO that exposes an entity instead of being mapped from it. */
public record EntityCarryingDto(FixtureEntity entity) {
}

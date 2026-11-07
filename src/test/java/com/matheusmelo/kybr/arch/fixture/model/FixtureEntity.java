package com.matheusmelo.kybr.arch.fixture.model;

import org.springframework.data.mongodb.core.mapping.Document;

/** Rule fixture: a persistent entity for controllers and DTOs to misuse. */
@Document("fixture")
public record FixtureEntity(String id, String name) {
}

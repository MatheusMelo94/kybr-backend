package com.matheusmelo.kybr.dto.admin;

import java.util.UUID;

/** A created workspace: its id and the slug it was assigned. */
public record AdminCreatedResponse(UUID id, String slug) {
}

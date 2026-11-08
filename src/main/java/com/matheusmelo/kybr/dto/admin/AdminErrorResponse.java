package com.matheusmelo.kybr.dto.admin;

/**
 * A refused admin call: {@code code} is {@code INVALID}, {@code NOT_FOUND}, {@code CONFLICT} or
 * {@code UNAVAILABLE}, {@code field} the input it concerns. Never an exception message or a stored value.
 */
public record AdminErrorResponse(String code, String field) {
}

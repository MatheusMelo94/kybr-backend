package com.matheusmelo.kybr.exception;

/** Where a rejected request value came from (it decides the status of some codes). */
public enum ParameterLocation {
    BODY,
    QUERY,
    PATH,
    HEADER
}

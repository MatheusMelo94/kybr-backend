package com.matheusmelo.kybr.arch.fixture.idempotency;

/** Rule fixture, never run: an idempotent handler that leaves its completion to a default. */
public class UndeclaredCompletionHandler {

    @Idempotent
    void create() {
    }
}

package com.matheusmelo.kybr.service.idempotency;

/**
 * {@code PANEL} routes: the session's workspace id. Interface only in B0; B3 implements it with
 * the session. An {@code @Idempotent} route naming it fails until then.
 */
public interface WorkspaceBindingResolver extends IdempotencyBindingResolver {
}

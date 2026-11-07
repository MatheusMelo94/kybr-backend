package com.matheusmelo.kybr.service.link;

/**
 * A validated request to start a verification.
 *
 * @param cnpj the CNPJ as received (already validated)
 * @param responsavel the responsible person as typed; normalised before storage
 * @param ref the integrator's reference, optional, stored untouched
 * @param extId the integrator's unverified external id, optional, stored untouched
 * @param idempotencyKey the request's idempotency key (a validated UUID)
 */
public record StartCommand(String cnpj, String responsavel, String ref, String extId, String idempotencyKey) {

    @Override
    public String toString() {
        return "StartCommand[redacted]";
    }
}

package com.matheusmelo.kybr.service.idempotency;

/**
 * A stored replay body can't be decrypted: wrong AAD (moved to another record), tampered bytes,
 * or a key no longer in the keyset. The caller answers 500 {@code ERRO_INTERNO} and never
 * re-executes the handler. The message carries no key or body material.
 */
public class ReplayBodyDecryptException extends RuntimeException {

    public ReplayBodyDecryptException(Throwable cause) {
        super("replay body decryption failed", cause);
    }
}

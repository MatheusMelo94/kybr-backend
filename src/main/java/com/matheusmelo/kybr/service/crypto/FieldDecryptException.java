package com.matheusmelo.kybr.service.crypto;

/**
 * An encrypted personal-data field can't be decrypted: a wrong AAD (moved to another document),
 * tampered bytes, or a key no longer in the keyset. The message carries no key or field material.
 */
public class FieldDecryptException extends RuntimeException {

    public FieldDecryptException(Throwable cause) {
        super("personal-data field decryption failed", cause);
    }
}

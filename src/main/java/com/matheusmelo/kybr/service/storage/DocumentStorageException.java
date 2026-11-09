package com.matheusmelo.kybr.service.storage;

/**
 * Document storage failed or couldn't be reached. Its message carries the operation and a status at
 * most: never a credential, a response body or file content.
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message) {
        super(message);
    }

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

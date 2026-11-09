package com.matheusmelo.kybr.service.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * Types an uploaded file by its first bytes, and only by them: the declared {@code Content-Type}
 * and the file name are never consulted. A PDF must start with {@code %PDF-} at offset 0 (nothing
 * before it, not even a byte-order mark), a JPEG with {@code FF D8 FF}, a PNG with its 8-byte
 * signature. Anything else is refused.
 */
public final class MagicBytes {

    /** The longest signature: all a detection ever reads. */
    static final int HEADER_LENGTH = 8;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** An accepted file type. */
    public enum Kind {
        PDF("application/pdf"),
        JPEG("image/jpeg"),
        PNG("image/png");

        private final String contentType;

        Kind(String contentType) {
            this.contentType = contentType;
        }

        /** The stored content type, derived from the bytes. */
        public String contentType() {
            return contentType;
        }

        /** Whether this is a photo (a page), as opposed to a PDF. */
        public boolean isPhoto() {
            return this != PDF;
        }
    }

    private MagicBytes() {
    }

    /** The type of a file whose first bytes are {@code content}, or empty when it isn't accepted. */
    public static Optional<Kind> detect(byte[] content) {
        if (startsWith(content, PDF)) {
            return Optional.of(Kind.PDF);
        }
        if (startsWith(content, JPEG)) {
            return Optional.of(Kind.JPEG);
        }
        if (startsWith(content, PNG)) {
            return Optional.of(Kind.PNG);
        }
        return Optional.empty();
    }

    /** Reads at most {@link #HEADER_LENGTH} bytes of {@code in} and types them. */
    public static Optional<Kind> detect(InputStream in) throws IOException {
        return detect(in.readNBytes(HEADER_LENGTH));
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length
                && Arrays.equals(content, 0, signature.length, signature, 0, signature.length);
    }
}

package com.matheusmelo.kybr.support.upload;

import java.nio.charset.StandardCharsets;

/** Small synthetic files for upload tests. */
public final class TestFiles {

    private TestFiles() {
    }

    /**
     * The standard 68-byte antivirus test file. Assembled at run time, so no source or class file
     * holds it whole. It isn't a PDF, JPEG or PNG, so it can only prove the type gate.
     */
    public static byte[] eicar() {
        return String.join("", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$", "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
    }
}

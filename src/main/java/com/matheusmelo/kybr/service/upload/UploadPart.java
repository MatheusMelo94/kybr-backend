package com.matheusmelo.kybr.service.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * One part of an upload's body, kept on disk. There is deliberately no way to read the part's file
 * name or declared content type: neither is ever consulted, stored or logged.
 */
public interface UploadPart {

    /** The form field name ({@code tipo} or {@code arquivos}). */
    String name();

    /** The content's length in bytes. */
    long size();

    /** The content, from the start; the caller closes it. */
    InputStream open() throws IOException;

    /** Removes the part's temporary file; safe to call more than once. */
    void delete();
}

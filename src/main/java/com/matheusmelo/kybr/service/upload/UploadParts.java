package com.matheusmelo.kybr.service.upload;

import java.util.List;

/**
 * An upload's multipart body, not yet parsed. Nothing is read or written to disk until
 * {@link #parse} is called, which the upload does only after every check that needs no body.
 */
public interface UploadParts {

    /**
     * Parses the body (every part to disk). A body the container refuses (too many parts, a part
     * header too large, malformed) throws the container's exception.
     */
    List<UploadPart> parse();
}

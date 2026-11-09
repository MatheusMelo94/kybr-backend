package com.matheusmelo.kybr.service.upload;

import com.matheusmelo.kybr.model.ClientIp;

/**
 * A document upload as received: the link slug and token from the path, the socket client IP, and
 * the unparsed body.
 */
public record UploadCommand(String slug, String token, ClientIp clientIp, UploadParts parts) {
}

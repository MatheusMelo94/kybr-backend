package com.matheusmelo.kybr.controller;

import com.matheusmelo.kybr.service.upload.UploadPart;
import com.matheusmelo.kybr.service.upload.UploadParts;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * An upload's multipart body as the container holds it, parsed only when the service asks
 * ({@code spring.servlet.multipart.resolve-lazily}). Parsing goes through Spring's multipart request
 * first, so the container's limits (part count, part header size, file size) fail the same way as
 * on any multipart route. A part's file name and declared content type are never read.
 */
final class ServletUploadParts implements UploadParts {

    private final HttpServletRequest request;

    ServletUploadParts(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public List<UploadPart> parse() {
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipart == null) {
            throw new MultipartException("not a multipart request");
        }
        multipart.getMultiFileMap(); // parses the lazily resolved body, translating container failures
        try {
            return multipart.getParts().stream().<UploadPart>map(ContainerPart::new).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't read the upload's parts", e);
        } catch (ServletException e) {
            throw new MultipartException("couldn't read the upload's parts", e);
        }
    }

    /** One part, on disk in the multipart temporary directory. */
    private record ContainerPart(Part part) implements UploadPart {

        @Override
        public String name() {
            return part.getName();
        }

        @Override
        public long size() {
            return part.getSize();
        }

        @Override
        public InputStream open() throws IOException {
            return part.getInputStream();
        }

        @Override
        public void delete() {
            try {
                part.delete();
            } catch (IOException e) {
                // the container removes it when the request ends; the directory is also wiped at startup
            }
        }
    }
}

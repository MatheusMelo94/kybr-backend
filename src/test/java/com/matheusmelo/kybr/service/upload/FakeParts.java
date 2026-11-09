package com.matheusmelo.kybr.service.upload;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A multipart body for service tests: counts parses and records which parts were deleted. */
final class FakeParts implements UploadParts {

    final List<FakePart> parts = new ArrayList<>();
    final AtomicInteger parses = new AtomicInteger();
    RuntimeException failure;

    static FakeParts of(String tipo, byte[]... files) {
        FakeParts body = new FakeParts();
        if (tipo != null) {
            body.text("tipo", tipo);
        }
        for (byte[] file : files) {
            body.file(file);
        }
        return body;
    }

    FakeParts text(String name, String value) {
        parts.add(new FakePart(name, value.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    FakeParts file(byte[] content) {
        parts.add(new FakePart(UploadService.FILES_FIELD, content));
        return this;
    }

    @Override
    public List<UploadPart> parse() {
        parses.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(parts);
    }

    boolean allDeleted() {
        return parts.stream().allMatch(part -> part.deleted);
    }

    static final class FakePart implements UploadPart {
        private final String name;
        private final byte[] content;
        boolean deleted;

        FakePart(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long size() {
            return content.length;
        }

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void delete() {
            deleted = true;
        }
    }
}

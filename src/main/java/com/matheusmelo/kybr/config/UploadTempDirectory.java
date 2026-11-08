package com.matheusmelo.kybr.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The directory multipart parts are written to ({@code kybr.upload.temp-dir}, also the container's
 * multipart location). Created if missing and emptied at startup: parts are whole seller documents,
 * so nothing a crashed run left behind may survive a restart. Uploads delete their own parts on every
 * exit; this is the backstop.
 */
@Component
public class UploadTempDirectory {

    private static final Logger log = LoggerFactory.getLogger(UploadTempDirectory.class);

    private final Path path;

    @Autowired
    public UploadTempDirectory(KybrProperties properties) {
        this(properties.upload().tempDir());
    }

    UploadTempDirectory(Path path) {
        this.path = path;
        try {
            Files.createDirectories(path);
            int removed = wipe(path);
            log.info("event=upload_temp_dir_ready removed={}", removed);
        } catch (IOException e) {
            throw new UncheckedIOException("the upload temp directory could not be prepared", e);
        }
    }

    public Path path() {
        return path;
    }

    /** Deletes everything under {@code dir}, deepest first, keeping {@code dir}; the regular files removed. */
    private static int wipe(Path dir) throws IOException {
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(dir)) {
            entries = walk.filter(entry -> !entry.equals(dir)).sorted(Comparator.reverseOrder()).toList();
        }
        int files = 0;
        for (Path entry : entries) {
            if (Files.isRegularFile(entry)) {
                files++;
            }
            Files.deleteIfExists(entry);
        }
        return files;
    }
}

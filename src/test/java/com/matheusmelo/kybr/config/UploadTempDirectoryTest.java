package com.matheusmelo.kybr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadTempDirectoryTest {

    @TempDir
    Path root;

    @Test
    void createsAMissingDirectory() {
        Path dir = root.resolve("a/b/uploads");

        new UploadTempDirectory(dir);

        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void wipesWhateverAPreviousRunLeftButKeepsTheDirectory() throws IOException {
        Path dir = Files.createDirectories(root.resolve("uploads"));
        Files.write(dir.resolve("upload_left_over.tmp"), new byte[] {1, 2, 3});
        Files.write(Files.createDirectories(dir.resolve("nested")).resolve("part.tmp"), new byte[] {4});

        UploadTempDirectory directory = new UploadTempDirectory(dir);

        assertThat(dir).isEmptyDirectory();
        assertThat(directory.path()).isEqualTo(dir);
    }
}

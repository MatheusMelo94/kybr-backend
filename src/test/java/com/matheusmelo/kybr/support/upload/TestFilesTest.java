package com.matheusmelo.kybr.support.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestFilesTest {

    @Test
    void eicarIsTheStandardSixtyEightByteTestFile() {
        byte[] eicar = TestFiles.eicar();

        assertThat(eicar).hasSize(68);
        assertThat(new String(eicar, StandardCharsets.US_ASCII)).startsWith("X5O!P%@AP[4").endsWith("$H+H*");
    }
}

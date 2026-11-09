package com.matheusmelo.kybr.service.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.upload.TestFiles;
import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MagicBytesTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static Stream<Arguments> files() {
        return Stream.of(
                Arguments.of("synthetic PDF", TestPdfs.plain(), MagicBytes.Kind.PDF),
                Arguments.of("PDF header only", ascii("%PDF-"), MagicBytes.Kind.PDF),
                Arguments.of("JPEG", TestImages.jpeg(8, 8), MagicBytes.Kind.JPEG),
                Arguments.of("PNG", TestImages.png(8, 8), MagicBytes.Kind.PNG),
                Arguments.of("HTML", ascii("<!doctype html><p>x"), null),
                Arguments.of("GIF", ascii("GIF89a\u0001\u0000\u0001\u0000"), null),
                Arguments.of("WebP", ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "), null),
                Arguments.of("ZIP", ascii("PK\u0003\u0004\u0014\u0000"), null),
                Arguments.of("PDF after a BOM", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, TestPdfs.plain()), null),
                Arguments.of("PDF after a space", concat(ascii(" "), TestPdfs.plain()), null),
                Arguments.of("PDF without the dash", ascii("%PDF1.7"), null),
                Arguments.of("truncated JPEG marker", new byte[] {(byte) 0xFF, (byte) 0xD8}, null),
                Arguments.of("truncated PNG signature", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A}, null),
                Arguments.of("HEIC", concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypheic")), null),
                Arguments.of("BMP", ascii("BM\u0036\u0000"), null),
                Arguments.of("TIFF little-endian", new byte[] {'I', 'I', 0x2A, 0}, null),
                Arguments.of("TIFF big-endian", new byte[] {'M', 'M', 0, 0x2A}, null),
                Arguments.of("SVG", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"), null),
                Arguments.of("DOCX", ascii("PK\u0003\u0004[Content_Types].xml"), null),
                Arguments.of("Windows executable", ascii("MZ\u0090\u0000"), null),
                Arguments.of("PDF without the dash, then text", ascii("%PDF1"), null),
                Arguments.of("a 4-byte PNG prefix", new byte[] {(byte) 0x89, 'P', 'N', 'G'}, null),
                Arguments.of("raw EICAR", TestFiles.eicar(), null),
                Arguments.of("empty", new byte[0], null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    void typesAFileByItsFirstBytesOnly(String name, byte[] content, MagicBytes.Kind expected) {
        assertThat(MagicBytes.detect(content)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void readsOnlyTheHeaderFromAStream() throws IOException {
        byte[] jpeg = TestImages.jpeg(64, 64);
        CountingStream in = new CountingStream(new ByteArrayInputStream(jpeg));

        assertThat(MagicBytes.detect(in)).contains(MagicBytes.Kind.JPEG);
        assertThat(in.read).isLessThanOrEqualTo(MagicBytes.HEADER_LENGTH);
    }

    @Test
    void eachKindHasItsContentType() {
        assertThat(MagicBytes.Kind.PDF.contentType()).isEqualTo("application/pdf");
        assertThat(MagicBytes.Kind.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(MagicBytes.Kind.PNG.contentType()).isEqualTo("image/png");
        assertThat(MagicBytes.Kind.PDF.isPhoto()).isFalse();
        assertThat(MagicBytes.Kind.JPEG.isPhoto()).isTrue();
        assertThat(MagicBytes.Kind.PNG.isPhoto()).isTrue();
    }

    private static final class CountingStream extends InputStream {
        private final InputStream in;
        private int read;

        CountingStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                read++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                read += n;
            }
            return n;
        }
    }
}

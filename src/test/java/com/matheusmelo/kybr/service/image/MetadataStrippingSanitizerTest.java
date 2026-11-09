package com.matheusmelo.kybr.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.support.upload.TestImages;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * The non-production stand-in: removes metadata segments and chunks by parsing the container only,
 * never decoding, so local and test runs need no sanitizer service.
 */
class MetadataStrippingSanitizerTest {

    private final MetadataStrippingSanitizer sanitizer = new MetadataStrippingSanitizer();

    private SanitizeResult sanitize(byte[] photo, ImageHeaderCheck.Format format) {
        return sanitizer.sanitize(new ByteArrayInputStream(photo), photo.length, format);
    }

    private static byte[] bytes(SanitizeResult result) {
        assertThat(result).isInstanceOf(SanitizeResult.Sanitized.class);
        return ((SanitizeResult.Sanitized) result).bytes();
    }

    @Test
    void aJpegLosesItsApp1ToApp15AndCommentSegmentsAndKeepsItsImageData() {
        byte[] original = withComment(TestImages.jpegWithMetadata(64, 48));

        SanitizeResult result = sanitize(original, ImageHeaderCheck.Format.JPEG);

        byte[] out = bytes(result);
        assertThat(((SanitizeResult.Sanitized) result).contentType()).isEqualTo("image/jpeg");
        assertThat(TestImages.jpegMarkers(out)).noneMatch(m -> m >= 0xE1 && m <= 0xEF).doesNotContain(0xFE)
                .contains(0xE0, 0xDB, 0xC0, 0xC4);
        assertThat(TestImages.containsBytes(out, TestImages.GPS_SENTINEL)).isFalse();
        assertThat(TestImages.containsBytes(out, "comentario")).isFalse();
        assertThat(TestImages.containsBytes(out, "xmpmeta")).isFalse();
        assertThat(Arrays.copyOfRange(out, out.length - 2, out.length)).containsExactly(0xFF, 0xD9);
        assertThat(afterScanStart(out)).isEqualTo(afterScanStart(original));
    }

    @Test
    void aPngLosesItsAncillaryChunksButKeepsTransparency() {
        byte[] original = withChunkAfterHeader(TestImages.pngWithTextChunks(40, 30), "tRNS", new byte[] {0, 0, 0, 0, 0, 0});

        byte[] out = bytes(sanitize(original, ImageHeaderCheck.Format.PNG));

        assertThat(TestImages.pngChunkTypes(out)).doesNotContain("tEXt", "iTXt", "eXIf").contains("IHDR", "tRNS", "IDAT")
                .endsWith("IEND");
        assertThat(TestImages.pngChunksHaveValidCrcs(out)).isTrue();
    }

    @Test
    void bytesAfterThePngEndAreDropped() {
        byte[] png = TestImages.png(8, 8);
        byte[] trailing = Arrays.copyOf(png, png.length + 5);

        assertThat(bytes(sanitize(trailing, ImageHeaderCheck.Format.PNG))).isEqualTo(png);
    }

    @Test
    void aMalformedJpegIsRefused() {
        byte[] bad = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x00, 1, 2};
        assertThat(sanitize(bad, ImageHeaderCheck.Format.JPEG)).isInstanceOf(SanitizeResult.Refused.class);
        assertThat(sanitize(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12}, ImageHeaderCheck.Format.JPEG))
                .isInstanceOf(SanitizeResult.Refused.class);
    }

    @Test
    void aJpegWithoutImageDataIsRefused() {
        byte[] noScan = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0, (byte) 0xFF, (byte) 0xD9};
        assertThat(sanitize(noScan, ImageHeaderCheck.Format.JPEG)).isInstanceOf(SanitizeResult.Refused.class);
    }

    @Test
    void endlessJpegSegmentsAreRefused() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        for (int i = 0; i < 200; i++) {
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE2, 0, 2});
        }
        assertThat(sanitize(out.toByteArray(), ImageHeaderCheck.Format.JPEG)).isInstanceOf(SanitizeResult.Refused.class);
    }

    @Test
    void aPngWithABrokenChunkLengthIsRefused() {
        byte[] png = TestImages.png(8, 8);
        png[33] = 0x7F; // the length of the chunk after IHDR
        assertThat(sanitize(png, ImageHeaderCheck.Format.PNG)).isInstanceOf(SanitizeResult.Refused.class);
    }

    @Test
    void aPngWithoutAnEndIsRefused() {
        byte[] png = TestImages.png(8, 8);
        assertThat(sanitize(Arrays.copyOf(png, png.length - 12), ImageHeaderCheck.Format.PNG))
                .isInstanceOf(SanitizeResult.Refused.class);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static byte[] withComment(byte[] jpeg) {
        byte[] text = "comentario sintetico".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (text.length + 2)});
        out.writeBytes(text);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] afterScanStart(byte[] jpeg) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
                return Arrays.copyOfRange(jpeg, i, jpeg.length);
            }
        }
        throw new AssertionError("no scan");
    }

    private static byte[] withChunkAfterHeader(byte[] png, String type, byte[] data) {
        int afterIhdr = 8 + 8 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }
}

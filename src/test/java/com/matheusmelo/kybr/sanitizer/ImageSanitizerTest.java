package com.matheusmelo.kybr.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import com.matheusmelo.kybr.support.upload.ImageHeaders;
import com.matheusmelo.kybr.support.upload.TestImages;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The sanitizer's output: freshly encoded pixels, no metadata, orientation applied, long edge capped. */
class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer(new ImageHeaderCheck(50_000_000, 12_000), 4_000);

    @Test
    void aJpegLosesEveryMetadataSegmentAndItsGpsBytes() {
        byte[] original = TestImages.jpegWithMetadata(64, 48);
        assertThat(TestImages.hasGps(original)).isTrue();
        assertThat(TestImages.hasMakerNote(original)).isTrue();
        assertThat(TestImages.hasXmp(original)).isTrue();

        ImageSanitizer.Sanitized out = sanitizer.sanitize(original);

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(TestImages.jpegMarkers(out.bytes()))
                .noneMatch(marker -> marker >= 0xE0 && marker <= 0xEF)
                .doesNotContain(0xFE);
        assertThat(TestImages.hasExif(out.bytes())).isFalse();
        assertThat(TestImages.containsBytes(out.bytes(), TestImages.GPS_SENTINEL)).isFalse();
        assertThat(TestImages.containsBytes(out.bytes(), "SINTETICO")).isFalse();
        assertThat(TestImages.containsBytes(out.bytes(), "xmpmeta")).isFalse();
    }

    @Test
    void theExifOrientationIsAppliedToThePixels() throws IOException {
        // a red block in the top-left corner as stored; orientation 6 means "rotate 90° clockwise to view"
        byte[] original = TestImages.withJpegMetadata(jpegWithRedCorner(64, 48));

        ImageSanitizer.Sanitized out = sanitizer.sanitize(original);

        BufferedImage viewed = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        assertThat(out.width()).isEqualTo(48);
        assertThat(out.height()).isEqualTo(64);
        assertThat(viewed.getWidth()).isEqualTo(48);
        assertThat(viewed.getHeight()).isEqualTo(64);
        assertThat(isRed(viewed.getRGB(43, 5))).as("top-right after rotation").isTrue();
        assertThat(isRed(viewed.getRGB(5, 5))).as("top-left after rotation").isFalse();
    }

    @Test
    void aPngLosesItsTextAndExifChunks() {
        byte[] original = TestImages.pngWithTextChunks(40, 30);
        assertThat(TestImages.pngChunkTypes(original)).contains("tEXt", "iTXt", "eXIf");

        ImageSanitizer.Sanitized out = sanitizer.sanitize(original);

        assertThat(out.contentType()).isEqualTo("image/png");
        assertThat(TestImages.pngChunkTypes(out.bytes())).isSubsetOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND");
        assertThat(TestImages.pngChunksHaveValidCrcs(out.bytes())).isTrue();
        assertThat(out.width()).isEqualTo(40);
        assertThat(out.height()).isEqualTo(30);
    }

    @Test
    void aLargePhotoComesOutWithAFourThousandPixelLongEdge() throws IOException {
        BufferedImage big = new BufferedImage(6_000, 8_000, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(big, "jpg", encoded);
        big = null;

        ImageSanitizer.Sanitized out = sanitizer.sanitize(encoded.toByteArray());

        assertThat(Math.max(out.width(), out.height())).isEqualTo(4_000);
        assertThat(out.width()).isEqualTo(3_000);
    }

    @Test
    void aSmallPhotoKeepsItsSize() {
        ImageSanitizer.Sanitized out = sanitizer.sanitize(TestImages.jpeg(120, 80));

        assertThat(out.width()).isEqualTo(120);
        assertThat(out.height()).isEqualTo(80);
    }

    @Test
    void aHeaderOverTheCapsIsRejectedBeforeAnyDecode() {
        assertThatThrownBy(() -> sanitizer.sanitize(ImageHeaders.jpeg(10_000, 6_000)))
                .isInstanceOfSatisfying(ImageSanitizer.SanitizeException.class,
                        e -> assertThat(e.outcome()).isEqualTo(ImageSanitizer.Outcome.REJECTED));
    }

    @Test
    void aCmykJpegIsUnsupportedBeforeAnyDecode() {
        assertThatThrownBy(() -> sanitizer.sanitize(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 4, null)))
                .isInstanceOfSatisfying(ImageSanitizer.SanitizeException.class,
                        e -> assertThat(e.outcome()).isEqualTo(ImageSanitizer.Outcome.UNSUPPORTED));
    }

    @Test
    void aValidHeaderOverGarbageIsRejected() {
        byte[] garbage = Arrays.copyOf(ImageHeaders.jpeg(64, 48), 4_000);
        Arrays.fill(garbage, 2_200, garbage.length, (byte) 0x5A);

        assertThatThrownBy(() -> sanitizer.sanitize(garbage))
                .isInstanceOfSatisfying(ImageSanitizer.SanitizeException.class,
                        e -> assertThat(e.outcome()).isEqualTo(ImageSanitizer.Outcome.REJECTED));
    }

    @Test
    void notAPhotoIsRejected() {
        assertThatThrownBy(() -> sanitizer.sanitize("%PDF-1.7".getBytes()))
                .isInstanceOfSatisfying(ImageSanitizer.SanitizeException.class,
                        e -> assertThat(e.outcome()).isEqualTo(ImageSanitizer.Outcome.REJECTED));
    }

    private static byte[] jpegWithRedCorner(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 16, 12);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 80 && c.getBlue() < 80;
    }
}

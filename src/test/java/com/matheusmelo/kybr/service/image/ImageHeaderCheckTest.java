package com.matheusmelo.kybr.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.service.image.ImageHeaderCheck.Result;
import com.matheusmelo.kybr.support.upload.ImageHeaders;
import com.matheusmelo.kybr.support.upload.TestImages;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Image size and encoding from the header alone, before any decode. */
class ImageHeaderCheckTest {

    private final ImageHeaderCheck check = new ImageHeaderCheck(50_000_000, 12_000);

    private Result check(byte[] image) throws IOException {
        return check.check(new ByteArrayInputStream(image));
    }

    // --- JPEG ----------------------------------------------------------------------------------

    @Test
    void aJpegWithinTheCapsPassesWithItsSize() throws IOException {
        assertThat(check(ImageHeaders.jpeg(8000, 6000))).isEqualTo(new Result.Ok(ImageHeaderCheck.Format.JPEG, 8000, 6000));
    }

    @Test
    void aRealEncodedJpegPasses() throws IOException {
        assertThat(check(TestImages.jpegWithMetadata(64, 48)))
                .isEqualTo(new Result.Ok(ImageHeaderCheck.Format.JPEG, 64, 48));
    }

    @Test
    void aProgressiveJpegPasses() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF2, 4000, 3000, 8, 3, null))).isInstanceOf(Result.Ok.class);
    }

    @Test
    void aGreyscaleJpegPasses() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 1, null))).isInstanceOf(Result.Ok.class);
    }

    @Test
    void exactlyTheMaximumPixelCountPassesAndOneMoreRowIsRefused() throws IOException {
        assertThat(check(ImageHeaders.jpeg(10_000, 5_000))).isInstanceOf(Result.Ok.class);
        assertThat(check(ImageHeaders.jpeg(10_000, 5_001))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void sixtyMegapixelsIsRefused() throws IOException {
        assertThat(check(ImageHeaders.jpeg(10_000, 6_000))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aSideOverTheMaximumIsRefusedEvenWhenTheAreaIsSmall() throws IOException {
        assertThat(check(ImageHeaders.jpeg(12_000, 100))).isInstanceOf(Result.Ok.class);
        assertThat(check(ImageHeaders.jpeg(13_000, 100))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.jpeg(100, 12_001))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aZeroDimensionIsRefused() throws IOException {
        assertThat(check(ImageHeaders.jpeg(0, 100))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.jpeg(100, 0))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aFourComponentJpegIsUnsupported() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 4, null))).isInstanceOf(Result.Unsupported.class);
    }

    @Test
    void anAdobeYcckTransformIsUnsupported() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 3, 2))).isInstanceOf(Result.Unsupported.class);
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 4, 0))).isInstanceOf(Result.Unsupported.class);
    }

    @Test
    void anAdobeYcbcrOrRgbTransformWithThreeComponentsPasses() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 3, 1))).isInstanceOf(Result.Ok.class);
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 8, 3, 0))).isInstanceOf(Result.Ok.class);
    }

    @Test
    void twelveBitPrecisionIsUnsupported() throws IOException {
        assertThat(check(ImageHeaders.jpeg(ImageHeaders.SOF0, 100, 100, 12, 3, null)))
                .isInstanceOf(Result.Unsupported.class);
        assertThat(check(ImageHeaders.jpeg(0xC1, 100, 100, 12, 3, null))).isInstanceOf(Result.Unsupported.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF})
    void arithmeticLosslessAndHierarchicalFramesAreUnsupported(int sof) throws IOException {
        assertThat(check(ImageHeaders.jpeg(sof, 100, 100, 8, 3, null))).isInstanceOf(Result.Unsupported.class);
    }

    @Test
    void anExtendedSequentialEightBitFramePasses() throws IOException {
        assertThat(check(ImageHeaders.jpeg(0xC1, 100, 100, 8, 3, null))).isInstanceOf(Result.Ok.class);
    }

    @Test
    void aTruncatedJpegIsRefused() throws IOException {
        byte[] whole = ImageHeaders.jpeg(800, 600);
        for (int length : new int[] {3, 4, 5, 20, whole.length - 30}) {
            assertThat(check(ImageHeaders.truncated(whole, length))).as("cut at %d", length)
                    .isInstanceOf(Result.Rejected.class);
        }
    }

    @Test
    void scanDataBeforeAnyFrameIsRefused() throws IOException {
        byte[] noFrame = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0};
        assertThat(check(noFrame)).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aSegmentLengthBelowTwoIsRefused() throws IOException {
        byte[] bad = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 0, 0, 0};
        assertThat(check(bad)).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void garbageWhereAMarkerShouldBeIsRefused() throws IOException {
        byte[] bad = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x11, 0x22, 0x33};
        assertThat(check(bad)).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void metadataSegmentsWithNoFrameHeaderAfterThemAreRefused() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        byte[] app = new byte[65_533];
        for (int i = 0; i < 40; i++) {
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE2, (byte) 0xFF, (byte) 0xFF});
            out.writeBytes(app);
        }
        assertThat(check(out.toByteArray())).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aPhotoWithMegabytesOfMetadataBeforeItsFrameHeaderPasses() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        byte[] app = new byte[65_533];
        for (int i = 0; i < 48; i++) { // about 3 MiB, the way depth maps travel in extended XMP
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) 0xFF, (byte) 0xFF});
            out.writeBytes(app);
        }
        byte[] photo = ImageHeaders.jpeg(1_600, 1_200);
        out.write(photo, 2, photo.length - 2);

        assertThat(check(out.toByteArray())).isEqualTo(new Result.Ok(ImageHeaderCheck.Format.JPEG, 1_600, 1_200));
    }

    @Test
    void moreSegmentsBeforeTheFrameHeaderThanTheBoundAreRefused() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        for (int i = 0; i < ImageHeaderCheck.MAX_SEGMENTS; i++) {
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, 0x00, 0x04, 0x00, 0x00});
        }
        byte[] photo = ImageHeaders.jpeg(1_600, 1_200);
        out.write(photo, 2, photo.length - 2);

        assertThat(check(out.toByteArray())).isEqualTo(
                new Result.Rejected("jpeg: too many segments before the frame header"));
    }

    // --- PNG -----------------------------------------------------------------------------------

    @Test
    void aPngWithinTheCapsPassesWithItsSize() throws IOException {
        assertThat(check(ImageHeaders.png(8000, 6000))).isEqualTo(new Result.Ok(ImageHeaderCheck.Format.PNG, 8000, 6000));
    }

    @Test
    void aRealEncodedPngWithTextChunksPasses() throws IOException {
        assertThat(check(TestImages.pngWithTextChunks(40, 30)))
                .isEqualTo(new Result.Ok(ImageHeaderCheck.Format.PNG, 40, 30));
    }

    @Test
    void aPngOverTheCapsIsRefused() throws IOException {
        assertThat(check(ImageHeaders.png(10_000, 6_000))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.png(13_000, 100))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.png(0x8000_0000L, 1))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aPngHeaderWithABadCrcIsRefused() throws IOException {
        assertThat(check(ImageHeaders.png(100, 100, 8, 2, false))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void anInvalidPngBitDepthForItsColourTypeIsRefused() throws IOException {
        assertThat(check(ImageHeaders.png(100, 100, 4, 2, true))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.png(100, 100, 16, 3, true))).isInstanceOf(Result.Rejected.class);
        assertThat(check(ImageHeaders.png(100, 100, 8, 5, true))).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aPngWhoseFirstChunkIsNotTheHeaderIsRefused() throws IOException {
        byte[] png = ImageHeaders.png(10, 10);
        png[12] = 'X';
        assertThat(check(png)).isInstanceOf(Result.Rejected.class);
    }

    @Test
    void aTruncatedPngIsRefused() throws IOException {
        byte[] png = ImageHeaders.png(10, 10);
        assertThat(check(java.util.Arrays.copyOf(png, 20))).isInstanceOf(Result.Rejected.class);
    }

    // --- anything else -------------------------------------------------------------------------

    @Test
    void aFileThatIsNeitherIsRefused() throws IOException {
        assertThat(check("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(Result.Rejected.class);
        assertThat(check(new byte[0])).isInstanceOf(Result.Rejected.class);
    }

    // --- no decoder ----------------------------------------------------------------------------

    @Test
    void theCheckLoadsNoImageDecoderClass() throws Exception {
        String classpath = String.join(File.pathSeparator, classesDir("classes"), classesDir("test-classes"));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m", "-Xshare:off",
                "-verbose:class", "-cp", classpath, ImageHeaderCheckProbe.class.getName()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(output).contains(ImageHeaderCheck.class.getName()).contains(ImageHeaderCheckProbe.DONE);
        assertThat(output).doesNotContain("javax.imageio").doesNotContain("java.awt.image").doesNotContain("sun.awt");
    }

    private static String classesDir(String name) {
        return Path.of(ImageHeaderCheckTest.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .getParent().resolve(name).toString();
    }
}

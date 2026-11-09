package com.matheusmelo.kybr.support.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The synthetic photos decode, and their metadata is verifiably there (so its removal can be proven). */
class TestImagesTest {

    @Test
    void aJpegWithMetadataDecodesAndCarriesExifGpsAMakerNoteXmpAndOrientationSix() throws IOException {
        byte[] jpeg = TestImages.jpegWithMetadata(64, 48);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(decoded.getWidth()).isEqualTo(64);
        assertThat(decoded.getHeight()).isEqualTo(48);
        assertThat(TestImages.hasExif(jpeg)).isTrue();
        assertThat(TestImages.hasGps(jpeg)).isTrue();
        assertThat(TestImages.hasMakerNote(jpeg)).isTrue();
        assertThat(TestImages.hasXmp(jpeg)).isTrue();
        assertThat(TestImages.exifOrientation(jpeg)).isEqualTo(6);
        assertThat(TestImages.containsBytes(jpeg, TestImages.GPS_SENTINEL)).isTrue();
    }

    @Test
    void aPlainJpegHasNoMetadata() throws IOException {
        byte[] jpeg = TestImages.jpeg(32, 32);

        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
        assertThat(TestImages.hasExif(jpeg)).isFalse();
        assertThat(TestImages.hasXmp(jpeg)).isFalse();
        assertThat(TestImages.exifOrientation(jpeg)).isNull();
    }

    @Test
    void aPngWithTextChunksDecodesAndCarriesTextInternationalTextAndExifChunks() throws IOException {
        byte[] png = TestImages.pngWithTextChunks(40, 30);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(TestImages.pngChunkTypes(png)).contains("IHDR", "tEXt", "iTXt", "eXIf", "IDAT", "IEND");
        assertThat(TestImages.pngChunksHaveValidCrcs(png)).isTrue();
        assertThat(TestImages.pngChunkTypes(TestImages.png(40, 30))).doesNotContain("tEXt", "iTXt", "eXIf");
    }
}

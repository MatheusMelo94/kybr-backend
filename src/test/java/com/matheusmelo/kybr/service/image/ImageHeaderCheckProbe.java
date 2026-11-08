package com.matheusmelo.kybr.service.image;

import com.matheusmelo.kybr.support.upload.ImageHeaders;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Runs the header check on every kind of header in a fresh JVM, so a test can list the classes it
 * loads ({@code -verbose:class}). Uses only hand-built headers: nothing here may touch ImageIO.
 */
public final class ImageHeaderCheckProbe {

    static final String DONE = "probe-done";

    private ImageHeaderCheckProbe() {
    }

    public static void main(String[] args) throws IOException {
        ImageHeaderCheck check = new ImageHeaderCheck(50_000_000, 12_000);
        byte[][] images = {
            ImageHeaders.jpeg(4000, 3000),
            ImageHeaders.jpeg(ImageHeaders.SOF2, 100, 100, 8, 4, 2),
            ImageHeaders.jpeg(0xC9, 100, 100, 12, 3, null),
            ImageHeaders.png(4000, 3000),
            ImageHeaders.png(100, 100, 8, 2, false),
        };
        for (byte[] image : images) {
            System.out.println(check.check(new ByteArrayInputStream(image)));
        }
        System.out.println(DONE);
    }
}

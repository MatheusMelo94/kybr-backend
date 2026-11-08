package com.matheusmelo.kybr.sanitizer;

import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The EXIF orientation of a JPEG (tag 0x0112 in IFD0 of the {@code Exif} APP1 segment), read by a
 * bounded parse of the segments before the image data, and the transform that applies it. Any
 * missing, malformed or out-of-range value is 1 (as stored).
 */
final class ExifOrientation {

    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int MAX_SEGMENTS = 64;

    private ExifOrientation() {
    }

    /** The orientation (1–8) of {@code jpeg}; 1 when absent or unreadable. */
    static int read(byte[] jpeg) {
        try {
            int at = 2;
            for (int segments = 0; segments < MAX_SEGMENTS && at + 4 <= jpeg.length; segments++) {
                if ((jpeg[at] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = jpeg[at + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;
                }
                int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
                if (length < 2 || at + 2 + length > jpeg.length) {
                    return 1;
                }
                if (marker == 0xE1 && length - 2 > EXIF.length
                        && Arrays.equals(jpeg, at + 4, at + 4 + EXIF.length, EXIF, 0, EXIF.length)) {
                    return orientation(ByteBuffer.wrap(jpeg, at + 4 + EXIF.length, length - 2 - EXIF.length).slice());
                }
                at += 2 + length;
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    private static int orientation(ByteBuffer tiff) {
        if (tiff.remaining() < 8) {
            return 1;
        }
        char order = (char) tiff.get(0);
        if (order == 'I' && tiff.get(1) == 'I') {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (order != 'M' || tiff.get(1) != 'M') {
            return 1;
        }
        int ifd = tiff.getInt(4);
        if (ifd < 8 || ifd + 2 > tiff.remaining()) {
            return 1;
        }
        int entries = tiff.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + 12 * i;
            if (entry + 12 > tiff.remaining()) {
                return 1;
            }
            if ((tiff.getShort(entry) & 0xFFFF) == ORIENTATION_TAG) {
                int value = tiff.getShort(entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    /** Whether the orientation swaps width and height. */
    static boolean swapsSides(int orientation) {
        return orientation >= 5;
    }

    /** Maps stored pixel coordinates of a {@code width} × {@code height} image to viewed ones. */
    static AffineTransform transform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }
}

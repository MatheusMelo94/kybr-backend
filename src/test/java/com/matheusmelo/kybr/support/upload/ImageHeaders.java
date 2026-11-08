package com.matheusmelo.kybr.support.upload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Hand-built image headers (no pixels, no ImageIO) for header checks: a JPEG with any frame marker,
 * size, precision and component count, optionally an Adobe APP14 segment; a PNG signature and
 * {@code IHDR} chunk with a good or bad CRC.
 */
public final class ImageHeaders {

    public static final int SOF0 = 0xC0;
    public static final int SOF2 = 0xC2;

    private ImageHeaders() {
    }

    /** A baseline 8-bit, 3-component JPEG header of the given size. */
    public static byte[] jpeg(int width, int height) {
        return jpeg(SOF0, width, height, 8, 3, null);
    }

    /**
     * @param sof the frame marker (0xC0–0xCF)
     * @param adobeTransform an APP14 Adobe segment with this transform before the frame; null for none
     */
    public static byte[] jpeg(int sof, int width, int height, int precision, int components, Integer adobeTransform) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        segment(out, 0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes(StandardCharsets.ISO_8859_1));
        segment(out, 0xE1, ("Exif\0\0" + "x".repeat(2000)).getBytes(StandardCharsets.ISO_8859_1));
        if (adobeTransform != null) {
            segment(out, 0xEE, new byte[] {'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, adobeTransform.byteValue()});
        }
        segment(out, 0xDB, new byte[65]);
        ByteBuffer frame = ByteBuffer.allocate(6 + 3 * components);
        frame.put((byte) precision).putShort((short) height).putShort((short) width).put((byte) components);
        for (int c = 1; c <= components; c++) {
            frame.put((byte) c).put((byte) 0x11).put((byte) 0);
        }
        segment(out, sof, frame.array());
        segment(out, 0xDA, new byte[] {1, 1, 0, 0, 63, 0});
        out.writeBytes(new byte[] {0x12, 0x34, 0x56});
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /** A JPEG cut after {@code length} bytes. */
    public static byte[] truncated(byte[] image, int length) {
        return java.util.Arrays.copyOf(image, length);
    }

    /** An 8-bit RGB PNG header ({@code IHDR} only) of the given size. */
    public static byte[] png(long width, long height) {
        return png(width, height, 8, 2, true);
    }

    public static byte[] png(long width, long height, int bitDepth, int colorType, boolean goodCrc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt((int) width).putInt((int) height).put((byte) bitDepth).put((byte) colorType)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] type = "IHDR".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.array());
        out.writeBytes(ByteBuffer.allocate(4).putInt(13).array());
        out.writeBytes(type);
        out.writeBytes(ihdr.array());
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue() ^ (goodCrc ? 0 : 1)).array());
        out.writeBytes(new byte[] {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82});
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.writeBytes(payload);
    }
}

package com.matheusmelo.kybr.service.image;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Reads a photo's size and encoding from its header, in pure Java and without decoding a pixel:
 * the JPEG frame header (SOFn) or the PNG {@code IHDR} chunk. It runs in the API, so it never uses an
 * image library; the photo is decoded only by the isolated sanitizer, and only after passing this.
 *
 * <ul>
 *   <li>{@link Result.Ok}: a baseline, extended or progressive 8-bit JPEG with 1 or 3 components, or
 *       a well-formed PNG, within {@code maxPixels} (width × height) and {@code maxSide};
 *   <li>{@link Result.Unsupported}: a JPEG the sanitizer's decoder can't read: CMYK or YCCK (4
 *       components, or an Adobe YCCK transform), a precision other than 8 bits, an arithmetic-coded,
 *       lossless or hierarchical frame;
 *   <li>{@link Result.Rejected}: over the caps, truncated, malformed, or neither format.
 * </ul>
 *
 * Reading is bounded: at most {@value #MAX_SEGMENTS} JPEG segments before the frame header, and at
 * most {@value #MAX_HEADER_BYTES} bytes read into memory. Metadata payloads are skipped without being
 * read and don't count toward that budget, so a phone photo carrying megabytes of metadata (a depth map
 * in extended XMP) still reaches its frame header; the file itself is already capped by the upload.
 */
public final class ImageHeaderCheck {

    /** The photo format, from its signature. */
    public enum Format { JPEG, PNG }

    /** The outcome; reasons are fixed texts for logs, never file content. */
    public sealed interface Result {

        /** Passed, with the size read from the header. */
        record Ok(Format format, int width, int height) implements Result {
        }

        /** Over the caps, truncated, malformed or not a photo. */
        record Rejected(String reason) implements Result {
        }

        /** A valid encoding the sanitizer can't decode. */
        record Unsupported(String reason) implements Result {
        }
    }

    static final int MAX_SEGMENTS = 256;
    static final int MAX_HEADER_BYTES = 512 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] IHDR = {'I', 'H', 'D', 'R'};
    private static final byte[] ADOBE = {'A', 'd', 'o', 'b', 'e'};
    /** Frame markers this decoder can't read: lossless, hierarchical and arithmetic-coded. */
    private static final Set<Integer> UNSUPPORTED_FRAMES = Set.of(0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD,
            0xCE, 0xCF);
    private static final int ADOBE_YCCK = 2;
    /** Valid PNG bit depths per colour type. */
    private static final Map<Integer, Set<Integer>> PNG_DEPTHS = Map.of(
            0, Set.of(1, 2, 4, 8, 16),
            2, Set.of(8, 16),
            3, Set.of(1, 2, 4, 8),
            4, Set.of(8, 16),
            6, Set.of(8, 16));

    private final long maxPixels;
    private final int maxSide;

    /**
     * @param maxPixels the largest width × height accepted
     * @param maxSide the largest width or height accepted
     */
    public ImageHeaderCheck(long maxPixels, int maxSide) {
        this.maxPixels = maxPixels;
        this.maxSide = maxSide;
    }

    /** Checks the photo whose bytes {@code in} starts with; reads only as far as its header. */
    public Result check(InputStream in) throws IOException {
        Reader reader = new Reader(in);
        try {
            int first = reader.u8();
            int second = reader.u8();
            if (first == 0xFF && second == 0xD8) {
                return jpeg(reader);
            }
            if (first == (PNG_SIGNATURE[0] & 0xFF) && second == PNG_SIGNATURE[1]) {
                return png(reader);
            }
            return new Result.Rejected("not a photo");
        } catch (Truncated e) {
            return new Result.Rejected(e.getMessage());
        }
    }

    private Result jpeg(Reader in) throws IOException {
        Integer adobeTransform = null;
        for (int segments = 0; segments < MAX_SEGMENTS; segments++) {
            if (in.u8() != 0xFF) {
                return new Result.Rejected("jpeg: no marker");
            }
            int marker;
            do {
                marker = in.u8();
            } while (marker == 0xFF);
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone, no length
            }
            if (marker == 0x00 || marker == 0xD8 || marker == 0xD9 || marker == 0xDA) {
                return new Result.Rejected("jpeg: no frame header before the image data");
            }
            int length = in.u16() - 2;
            if (length < 0) {
                return new Result.Rejected("jpeg: bad segment length");
            }
            if (isFrame(marker)) {
                return frame(marker, in.bytes(length), adobeTransform);
            }
            if (marker == 0xEE && length >= 12) {
                byte[] app14 = in.bytes(length);
                if (Arrays.equals(app14, 0, ADOBE.length, ADOBE, 0, ADOBE.length)) {
                    adobeTransform = app14[11] & 0xFF;
                }
            } else {
                in.skip(length);
            }
        }
        return new Result.Rejected("jpeg: too many segments before the frame header");
    }

    private static boolean isFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private Result frame(int marker, byte[] sof, Integer adobeTransform) {
        if (sof.length < 6) {
            return new Result.Rejected("jpeg: short frame header");
        }
        int precision = sof[0] & 0xFF;
        int height = ((sof[1] & 0xFF) << 8) | (sof[2] & 0xFF);
        int width = ((sof[3] & 0xFF) << 8) | (sof[4] & 0xFF);
        int components = sof[5] & 0xFF;
        if (sof.length < 6 + 3 * components) {
            return new Result.Rejected("jpeg: short frame header");
        }
        if (UNSUPPORTED_FRAMES.contains(marker)) {
            return new Result.Unsupported("jpeg: lossless, hierarchical or arithmetic-coded");
        }
        if (precision != 8) {
            return new Result.Unsupported("jpeg: precision other than 8 bits");
        }
        if (components == 4 || (adobeTransform != null && adobeTransform == ADOBE_YCCK)) {
            return new Result.Unsupported("jpeg: CMYK or YCCK");
        }
        if (components != 1 && components != 3) {
            return new Result.Rejected("jpeg: unexpected component count");
        }
        return sized(Format.JPEG, width, height);
    }

    private Result png(Reader in) throws IOException {
        byte[] rest = in.bytes(PNG_SIGNATURE.length - 2);
        if (!Arrays.equals(rest, 0, rest.length, PNG_SIGNATURE, 2, PNG_SIGNATURE.length)) {
            return new Result.Rejected("png: bad signature");
        }
        byte[] chunk = in.bytes(4 + 4 + 13 + 4);
        long length = u32(chunk, 0);
        if (length != 13 || !Arrays.equals(chunk, 4, 8, IHDR, 0, 4)) {
            return new Result.Rejected("png: the first chunk is not a header");
        }
        CRC32 crc = new CRC32();
        crc.update(chunk, 4, 4 + 13);
        if (crc.getValue() != u32(chunk, 21)) {
            return new Result.Rejected("png: bad header checksum");
        }
        long width = u32(chunk, 8);
        long height = u32(chunk, 12);
        int depth = chunk[16] & 0xFF;
        int colourType = chunk[17] & 0xFF;
        Set<Integer> depths = PNG_DEPTHS.get(colourType);
        if (depths == null || !depths.contains(depth) || chunk[18] != 0 || chunk[19] != 0
                || (chunk[20] != 0 && chunk[20] != 1)) {
            return new Result.Rejected("png: invalid header fields");
        }
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            return new Result.Rejected("png: size out of range");
        }
        return sized(Format.PNG, (int) width, (int) height);
    }

    private Result sized(Format format, int width, int height) {
        if (width < 1 || height < 1) {
            return new Result.Rejected("zero width or height");
        }
        if (width > maxSide || height > maxSide || (long) width * height > maxPixels) {
            return new Result.Rejected("over the size caps");
        }
        return new Result.Ok(format, width, height);
    }

    private static long u32(byte[] bytes, int at) {
        return ((long) (bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8)
                | (bytes[at + 3] & 0xFF);
    }

    /** Thrown inside the check for a header cut short or too long; never escapes it. */
    private static final class Truncated extends IOException {
        Truncated(String message) {
            super(message);
        }
    }

    /**
     * Reads at most {@value #MAX_HEADER_BYTES} bytes into memory; skipped bytes aren't counted. The end
     * of the stream is a truncated header.
     */
    private static final class Reader {
        private final InputStream in;
        private int consumed;

        Reader(InputStream in) {
            this.in = in;
        }

        int u8() throws IOException {
            count(1);
            int b = in.read();
            if (b < 0) {
                throw new Truncated("truncated header");
            }
            return b;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        byte[] bytes(int n) throws IOException {
            count(n);
            byte[] bytes = in.readNBytes(n);
            if (bytes.length < n) {
                throw new Truncated("truncated header");
            }
            return bytes;
        }

        void skip(int n) throws IOException {
            try {
                in.skipNBytes(n);
            } catch (EOFException e) {
                throw new Truncated("truncated header");
            }
        }

        private void count(int n) throws Truncated {
            consumed += n;
            if (consumed > MAX_HEADER_BYTES) {
                throw new Truncated("header too long");
            }
        }
    }
}

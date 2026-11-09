package com.matheusmelo.kybr.service.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The photo sanitizer stand-in ({@code kybr.image.mode=in-process-test}: local, docker, test and
 * staging by default; never in prod, where the bean doesn't exist and the profile guard requires
 * the remote sanitizer). Pure Java, and it never decodes: it walks the file's container and drops
 * metadata, copying everything else byte for byte.
 *
 * <ul>
 *   <li>JPEG: drops APP1–APP15 and comment segments, before and between scans, and anything after
 *       the end-of-image marker; keeps the JFIF header, tables, frame and scan data;
 *   <li>PNG: keeps the critical chunks and {@code tRNS}; drops every other ancillary chunk
 *       ({@code tEXt}, {@code iTXt}, {@code zTXt}, {@code eXIf}, {@code tIME}, ...) and anything after
 *       {@code IEND}.
 * </ul>
 *
 * It doesn't apply the EXIF orientation or downscale (the real sanitizer does), so it suits
 * synthetic test photos only. Parsing is bounded; a malformed container is
 * {@link SanitizeResult.Refused}.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "kybr.image", name = "mode", havingValue = "in-process-test")
public class MetadataStrippingSanitizer implements PhotoSanitizer {

    static final int MAX_SEGMENTS = 256;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Thrown inside the walk for a malformed container; never escapes. */
    private static final class Malformed extends Exception {
        Malformed() {
            super(null, null, false, false);
        }
    }

    @Override
    public SanitizeResult sanitize(InputStream original, long size, ImageHeaderCheck.Format format) {
        try {
            byte[] bytes = original.readAllBytes();
            return switch (format) {
                case JPEG -> new SanitizeResult.Sanitized(jpeg(bytes), "image/jpeg");
                case PNG -> new SanitizeResult.Sanitized(png(bytes), "image/png");
            };
        } catch (Malformed e) {
            return new SanitizeResult.Refused();
        } catch (IOException e) {
            return new SanitizeResult.Unavailable(false);
        }
    }

    private static byte[] jpeg(byte[] in) throws Malformed {
        if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != 0xD8) {
            throw new Malformed();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 2);
        int at = 2;
        boolean sawScan = false;
        for (int segments = 0; segments < MAX_SEGMENTS; segments++) {
            if (at + 1 >= in.length || (in[at] & 0xFF) != 0xFF) {
                throw new Malformed();
            }
            while ((in[at + 1] & 0xFF) == 0xFF) { // fill bytes
                if (++at + 1 >= in.length) {
                    throw new Malformed();
                }
            }
            int marker = in[at + 1] & 0xFF;
            if (marker == 0xD9) {
                if (!sawScan) {
                    throw new Malformed();
                }
                out.write(in, at, 2);
                return out.toByteArray(); // nothing after the end of the image
            }
            if (marker == 0x00 || marker == 0xD8) {
                throw new Malformed();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(in, at, 2);
                at += 2;
                continue;
            }
            if (at + 4 > in.length) {
                throw new Malformed();
            }
            int length = ((in[at + 2] & 0xFF) << 8) | (in[at + 3] & 0xFF);
            if (length < 2 || at + 2 + length > in.length) {
                throw new Malformed();
            }
            boolean metadata = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
            if (!metadata) {
                out.write(in, at, 2 + length);
            }
            at += 2 + length;
            if (marker == 0xDA) {
                sawScan = true;
                at = copyScanData(in, at, out);
            }
        }
        throw new Malformed();
    }

    /** Copies entropy-coded data up to the next real marker; returns its offset. */
    private static int copyScanData(byte[] in, int from, ByteArrayOutputStream out) throws Malformed {
        int at = from;
        while (at + 1 < in.length) {
            if ((in[at] & 0xFF) == 0xFF) {
                int next = in[at + 1] & 0xFF;
                if (next != 0x00 && next != 0xFF && (next < 0xD0 || next > 0xD7)) {
                    out.write(in, from, at - from);
                    return at;
                }
            }
            at++;
        }
        throw new Malformed(); // no end-of-image marker
    }

    private static byte[] png(byte[] in) throws Malformed {
        if (in.length < PNG_SIGNATURE.length || !Arrays.equals(in, 0, 8, PNG_SIGNATURE, 0, 8)) {
            throw new Malformed();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int at = 8;
        boolean first = true;
        while (true) {
            if (at + 12 > in.length) {
                throw new Malformed();
            }
            long length = ((long) (in[at] & 0xFF) << 24) | ((in[at + 1] & 0xFF) << 16) | ((in[at + 2] & 0xFF) << 8)
                    | (in[at + 3] & 0xFF);
            if (length > in.length - at - 12L) {
                throw new Malformed();
            }
            String type = new String(in, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (!type.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
                    || (first && !type.equals("IHDR"))) {
                throw new Malformed();
            }
            first = false;
            boolean critical = Character.isUpperCase(type.charAt(0));
            if (critical || type.equals("tRNS")) {
                out.write(in, at, 12 + (int) length);
            }
            at += 12 + (int) length;
            if (type.equals("IEND")) {
                return out.toByteArray(); // nothing after the end
            }
        }
    }
}

package com.matheusmelo.kybr.support.upload;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Small synthetic photos, with or without the metadata a sanitizer must strip, plus readers that
 * prove the metadata is there (or gone). JPEG metadata: an EXIF block (orientation 6, a GPS position
 * whose processing method carries {@link #GPS_SENTINEL}, a maker note) and an XMP packet. PNG
 * metadata: {@code tEXt}, {@code iTXt} and {@code eXIf} chunks. Test code only: it uses ImageIO.
 */
public final class TestImages {

    /** An ASCII run inside the GPS data: if it survives anywhere in the output, GPS bytes survived. */
    public static final String GPS_SENTINEL = "KYBR-GPS-SENTINEL";
    private static final String XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0";
    private static final int ORIENTATION = 0x0112;
    private static final int EXIF_IFD = 0x8769;
    private static final int GPS_IFD = 0x8825;
    private static final int MAKER_NOTE = 0x927C;

    private TestImages() {
    }

    /** A baseline JPEG with no metadata beyond the JFIF header. */
    public static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    /** A JPEG carrying EXIF (orientation 6, GPS, maker note) and XMP right after its JFIF header. */
    public static byte[] jpegWithMetadata(int width, int height) {
        return withJpegMetadata(jpeg(width, height));
    }

    /** {@code plain} (a JPEG starting with a JFIF header) with the EXIF and XMP blocks added. */
    public static byte[] withJpegMetadata(byte[] plain) {
        int afterApp0 = 2 + 2 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterApp0);
        writeSegment(out, 0xE1, concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), exif()));
        writeSegment(out, 0xE1, (XMP_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description"
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:creator=\"Sintetico\"/></x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8));
        out.write(plain, afterApp0, plain.length - afterApp0);
        return out.toByteArray();
    }

    /** A PNG with no ancillary chunks. */
    public static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    /** A PNG with {@code tEXt}, {@code iTXt} and {@code eXIf} chunks right after its header chunk. */
    public static byte[] pngWithTextChunks(int width, int height) {
        byte[] plain = png(width, height);
        int afterIhdr = 8 + 8 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        writeChunk(out, "tEXt", "Comment\0texto sintetico".getBytes(StandardCharsets.ISO_8859_1));
        writeChunk(out, "iTXt", "Description\0\0\0\0\0descrição sintética"
                .getBytes(StandardCharsets.UTF_8));
        writeChunk(out, "eXIf", exif());
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        return out.toByteArray();
    }

    // ---- readers ------------------------------------------------------------------------------

    public static boolean hasExif(byte[] jpeg) {
        return exifTiff(jpeg) != null;
    }

    public static boolean hasXmp(byte[] jpeg) {
        return app1Payloads(jpeg).stream().anyMatch(p -> startsWith(p, XMP_HEADER.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean hasGps(byte[] jpeg) {
        ByteBuffer tiff = exifTiff(jpeg);
        return tiff != null && tagValue(tiff, tiff.getInt(4), GPS_IFD) != null;
    }

    public static boolean hasMakerNote(byte[] jpeg) {
        ByteBuffer tiff = exifTiff(jpeg);
        Integer exifIfd = tiff == null ? null : tagValue(tiff, tiff.getInt(4), EXIF_IFD);
        return exifIfd != null && tagValue(tiff, exifIfd, MAKER_NOTE) != null;
    }

    /** The EXIF orientation, or null without one. */
    public static Integer exifOrientation(byte[] jpeg) {
        ByteBuffer tiff = exifTiff(jpeg);
        return tiff == null ? null : tagValue(tiff, tiff.getInt(4), ORIENTATION);
    }

    /** The JPEG's segment markers up to the image data, e.g. {@code 0xE0} for APP0. */
    public static List<Integer> jpegMarkers(byte[] jpeg) {
        return segments(jpeg).stream().map(segment -> segment[0] & 0xFF).toList();
    }

    public static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        for (int at = 8; at + 8 <= png.length;) {
            int length = ByteBuffer.wrap(png, at, 4).getInt();
            types.add(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1));
            at += 12 + length;
        }
        return types;
    }

    public static boolean pngChunksHaveValidCrcs(byte[] png) {
        for (int at = 8; at + 8 <= png.length;) {
            int length = ByteBuffer.wrap(png, at, 4).getInt();
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, 4 + length);
            if ((int) crc.getValue() != ByteBuffer.wrap(png, at + 8 + length, 4).getInt()) {
                return false;
            }
            at += 12 + length;
        }
        return true;
    }

    public static boolean containsBytes(byte[] haystack, String asciiNeedle) {
        byte[] needle = asciiNeedle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ---- building -----------------------------------------------------------------------------

    private static byte[] encode(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, width, height, Color.BLUE));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no " + format + " writer");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A big-endian TIFF block: IFD0 (orientation, EXIF and GPS pointers), the EXIF IFD, the GPS IFD. */
    private static byte[] exif() {
        byte[] makerNote = "SINTETICO-MAKERNOTE-DADOS".getBytes(StandardCharsets.US_ASCII);
        byte[] method = ("ASCII\0\0\0" + GPS_SENTINEL).getBytes(StandardCharsets.US_ASCII);
        int ifd0 = 8;
        int exifIfd = ifd0 + 2 + 3 * 12 + 4;
        int gpsIfd = exifIfd + 2 + 12 + 4;
        int data = gpsIfd + 2 + 5 * 12 + 4;
        int makerNoteAt = data;
        int latitudeAt = makerNoteAt + makerNote.length;
        int longitudeAt = latitudeAt + 24;
        int methodAt = longitudeAt + 24;
        ByteBuffer tiff = ByteBuffer.allocate(methodAt + method.length);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(ifd0);
        tiff.putShort((short) 3);
        entry(tiff, ORIENTATION, 3, 1, 6 << 16);
        entry(tiff, EXIF_IFD, 4, 1, exifIfd);
        entry(tiff, GPS_IFD, 4, 1, gpsIfd);
        tiff.putInt(0);
        tiff.putShort((short) 1);
        entry(tiff, MAKER_NOTE, 7, makerNote.length, makerNoteAt);
        tiff.putInt(0);
        tiff.putShort((short) 5);
        entry(tiff, 0x0001, 2, 2, 'S' << 24);
        entry(tiff, 0x0002, 5, 3, latitudeAt);
        entry(tiff, 0x0003, 2, 2, 'W' << 24);
        entry(tiff, 0x0004, 5, 3, longitudeAt);
        entry(tiff, 0x001B, 7, method.length, methodAt);
        tiff.putInt(0);
        tiff.put(makerNote);
        for (int value : new int[] {23, 1, 32, 1, 10, 1, 46, 1, 38, 1, 20, 1}) {
            tiff.putInt(value);
        }
        tiff.put(method);
        return tiff.array();
    }

    private static void entry(ByteBuffer tiff, int tag, int type, int count, int value) {
        tiff.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    private static void writeSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.writeBytes(payload);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // ---- parsing ------------------------------------------------------------------------------

    /** Each segment before the image data: {marker, payload...}. */
    private static List<byte[]> segments(byte[] jpeg) {
        List<byte[]> segments = new ArrayList<>();
        for (int at = 2; at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF;) {
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            byte[] segment = new byte[length - 1];
            segment[0] = (byte) marker;
            System.arraycopy(jpeg, at + 4, segment, 1, length - 2);
            segments.add(segment);
            at += 2 + length;
        }
        return segments;
    }

    private static List<byte[]> app1Payloads(byte[] jpeg) {
        return segments(jpeg).stream().filter(s -> (s[0] & 0xFF) == 0xE1)
                .map(s -> java.util.Arrays.copyOfRange(s, 1, s.length)).toList();
    }

    private static ByteBuffer exifTiff(byte[] jpeg) {
        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        return app1Payloads(jpeg).stream().filter(p -> startsWith(p, header))
                .map(p -> ByteBuffer.wrap(java.util.Arrays.copyOfRange(p, header.length, p.length)))
                .findFirst().orElse(null);
    }

    /** The value (or value offset) of {@code tag} in the IFD at {@code ifd}; a SHORT is read from the high half. */
    private static Integer tagValue(ByteBuffer tiff, int ifd, int tag) {
        int count = tiff.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int at = ifd + 2 + i * 12;
            if ((tiff.getShort(at) & 0xFFFF) == tag) {
                int type = tiff.getShort(at + 2) & 0xFFFF;
                return type == 3 ? tiff.getShort(at + 8) & 0xFFFF : tiff.getInt(at + 8);
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }
}

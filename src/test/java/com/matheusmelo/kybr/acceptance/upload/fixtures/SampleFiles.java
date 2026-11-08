package com.matheusmelo.kybr.acceptance.upload.fixtures;

import com.matheusmelo.kybr.support.upload.TestImages;
import com.matheusmelo.kybr.support.upload.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synthetic upload files, built in memory at test time and never written to the repository: PDFs
 * and photos of an exact byte size, photos made distinct by a label (so the duplicate guard treats
 * them as different files), photos with data after the end-of-image marker, and files whose first
 * bytes are not a PDF, JPEG or PNG.
 */
public final class SampleFiles {

    private static final int MAX_COMMENT_PAYLOAD = 65_533;

    private SampleFiles() {
    }

    /** A small valid PDF; {@code label} goes into a comment line, so different labels give different files. */
    public static byte[] pdf(String label) {
        return withPdfComment(TestPdfs.plain(), label);
    }

    /** A valid PDF of exactly {@code size} bytes, padded with one comment line after the header. */
    public static byte[] pdfOfExactSize(int size) {
        byte[] plain = TestPdfs.plain();
        int padding = size - plain.length - 2; // "%" + filler + "\n"
        if (padding < 0) {
            throw new IllegalArgumentException("a PDF can't be smaller than " + (plain.length + 2) + " bytes");
        }
        return withPdfComment(plain, "k".repeat(padding));
    }

    /** A 16 x 16 JPEG with {@code label} in a comment segment. */
    public static byte[] photo(String label) {
        return withComments(TestImages.jpeg(16, 16), label.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A JPEG of exactly {@code size} bytes: {@link #photo(String)} padded with comment segments placed
     * after the frame header (right before the scan), where a header reader has already found the size.
     */
    public static byte[] photoOfExactSize(int size, String label) {
        byte[] base = photo(label);
        int remaining = size - base.length;
        if (remaining < 0) {
            throw new IllegalArgumentException("a photo can't be smaller than " + base.length + " bytes");
        }
        ByteArrayOutputStream padding = new ByteArrayOutputStream(remaining);
        while (remaining > 0) {
            int segment = Math.min(remaining, MAX_COMMENT_PAYLOAD + 4);
            if (remaining - segment > 0 && remaining - segment < 4) {
                segment -= 4;
            }
            if (segment < 4) {
                throw new IllegalArgumentException("can't pad by " + remaining + " bytes");
            }
            int payload = segment - 4;
            padding.write(0xFF);
            padding.write(0xFE);
            padding.write((payload + 2) >> 8);
            padding.write((payload + 2) & 0xFF);
            byte[] filler = new byte[payload];
            Arrays.fill(filler, (byte) 'p');
            padding.writeBytes(filler);
            remaining -= segment;
        }
        return insertBeforeScan(base, padding.toByteArray());
    }

    /** {@link #photo(String)} with {@code trailer} appended after the end-of-image marker. */
    public static byte[] photoWithTrailer(String label, String trailer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(photo(label));
        out.writeBytes(trailer.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** A PNG of the given size (distinct sizes give distinct files). */
    public static byte[] png(int width, int height) {
        return TestImages.png(width, height);
    }

    /**
     * Files whose leading bytes are not {@code %PDF-} at offset 0, {@code FF D8 FF} or the full 8-byte
     * PNG signature, by name: other image and document formats, an empty file, near-misses of the PDF
     * and PNG signatures.
     */
    public static Map<String, byte[]> wrongMagic() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("gif", ascii("GIF89a\1\0\1\0\0\0\0;"));
        files.put("webp", ascii("RIFF$\0\0\0WEBPVP8 \u0018\0\0\0"));
        files.put("heic", concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypheic\0\0\0\0mif1heic")));
        files.put("bmp", ascii("BM6\0\0\0\0\0\0\06\0\0\0"));
        files.put("tiff", ascii("II*\0\u0008\0\0\0"));
        files.put("html", ascii("<!doctype html><html><body>sintetico</body></html>"));
        files.put("svg", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        files.put("zip", ascii("PK\u0003\u0004\u0014\0\0\0\0\0"));
        files.put("docx", ascii("PK\u0003\u0004\u0014\0\u0006\0[Content_Types].xml"));
        files.put("exe", ascii("MZ\u0090\0\u0003\0\0\0"));
        files.put("empty", new byte[0]);
        files.put("pdf-after-bom", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, TestPdfs.plain()));
        files.put("pdf-after-space", concat(ascii(" "), TestPdfs.plain()));
        files.put("pdf-without-dash", ascii("%PDF1.7\n%%EOF\n"));
        files.put("png-four-bytes", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        return files;
    }

    // ---- building -------------------------------------------------------------------------------

    private static byte[] withPdfComment(byte[] pdf, String comment) {
        int afterHeader = indexOf(pdf, (byte) '\n') + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + comment.length() + 2);
        out.write(pdf, 0, afterHeader);
        out.writeBytes(("%" + comment + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(pdf, afterHeader, pdf.length - afterHeader);
        return out.toByteArray();
    }

    private static byte[] withComments(byte[] jpeg, byte[] comment) {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xFE);
        segment.write((comment.length + 2) >> 8);
        segment.write((comment.length + 2) & 0xFF);
        segment.writeBytes(comment);
        return insertAfterSoi(jpeg, segment.toByteArray());
    }

    private static byte[] insertAfterSoi(byte[] jpeg, byte[] segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segments.length);
        out.write(jpeg, 0, 2);
        out.writeBytes(segments);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] insertBeforeScan(byte[] jpeg, byte[] segments) {
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segments.length);
                out.write(jpeg, 0, pos);
                out.writeBytes(segments);
                out.write(jpeg, pos, jpeg.length - pos);
                return out.toByteArray();
            }
            pos += 2 + (((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF));
        }
        throw new IllegalArgumentException("no start-of-scan marker");
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        throw new IllegalArgumentException("not found");
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

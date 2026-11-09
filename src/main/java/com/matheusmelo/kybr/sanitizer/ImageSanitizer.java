package com.matheusmelo.kybr.sanitizer;

import com.matheusmelo.kybr.service.image.ImageHeaderCheck;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.Node;

/**
 * Re-encodes a scanned photo so that nothing but its pixels survives. Runs only in the sanitizer
 * service in the isolated scanner organisation: the decoder is native code, so it never runs in the
 * API.
 *
 * <ol>
 *   <li>the header check again (size caps and supported encodings), before any decode;
 *   <li>a subsampled decode, so the long edge is at most {@code targetLongEdge} and memory stays
 *       bounded; metadata is never read;
 *   <li>the EXIF orientation applied to the pixels;
 *   <li>a fresh JPEG (quality 0.9, no APPn or comment segments, not even JFIF) or PNG (no ancillary
 *       chunks).
 * </ol>
 */
public final class ImageSanitizer {

    /** Why a photo was refused. */
    public enum Outcome { REJECTED, UNSUPPORTED }

    /** A refused photo; the message is a fixed text, never file content. */
    public static final class SanitizeException extends RuntimeException {
        private final Outcome outcome;

        public SanitizeException(Outcome outcome, String message) {
            super(message);
            this.outcome = outcome;
        }

        public Outcome outcome() {
            return outcome;
        }
    }

    /** The re-encoded photo. */
    public record Sanitized(byte[] bytes, String contentType, int width, int height) {
    }

    private static final String JPEG_NATIVE = "javax_imageio_jpeg_image_1.0";
    private static final float JPEG_QUALITY = 0.9f;

    private final ImageHeaderCheck headerCheck;
    private final int targetLongEdge;

    public ImageSanitizer(ImageHeaderCheck headerCheck, int targetLongEdge) {
        this.headerCheck = headerCheck;
        this.targetLongEdge = targetLongEdge;
    }

    /**
     * @throws SanitizeException when the photo fails the header check or can't be decoded
     */
    public Sanitized sanitize(byte[] original) {
        ImageHeaderCheck.Result.Ok header = header(original);
        boolean jpeg = header.format() == ImageHeaderCheck.Format.JPEG;
        int orientation = jpeg ? ExifOrientation.read(original) : 1;
        BufferedImage decoded = decode(original, header);
        BufferedImage viewed = orient(decoded, orientation);
        byte[] bytes = jpeg ? writeJpeg(viewed) : writePng(viewed);
        return new Sanitized(bytes, jpeg ? "image/jpeg" : "image/png", viewed.getWidth(), viewed.getHeight());
    }

    private ImageHeaderCheck.Result.Ok header(byte[] original) {
        ImageHeaderCheck.Result result;
        try {
            result = headerCheck.check(new ByteArrayInputStream(original));
        } catch (IOException e) {
            throw new SanitizeException(Outcome.REJECTED, "unreadable header");
        }
        return switch (result) {
            case ImageHeaderCheck.Result.Ok ok -> ok;
            case ImageHeaderCheck.Result.Unsupported unsupported ->
                    throw new SanitizeException(Outcome.UNSUPPORTED, unsupported.reason());
            case ImageHeaderCheck.Result.Rejected rejected ->
                    throw new SanitizeException(Outcome.REJECTED, rejected.reason());
        };
    }

    private BufferedImage decode(byte[] original, ImageHeaderCheck.Result.Ok header) {
        String format = header.format() == ImageHeaderCheck.Format.JPEG ? "jpeg" : "png";
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new IllegalStateException("no " + format + " decoder");
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            reader.setInput(in, true, true); // never reads metadata
            if (reader.getWidth(0) != header.width() || reader.getHeight(0) != header.height()) {
                throw new SanitizeException(Outcome.REJECTED, "size differs from the header");
            }
            int longEdge = Math.max(header.width(), header.height());
            int factor = Math.max(1, (longEdge + targetLongEdge - 1) / targetLongEdge);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(factor, factor, 0, 0);
            return reader.read(0, param);
        } catch (IOException | RuntimeException e) {
            if (e instanceof SanitizeException refused) {
                throw refused;
            }
            throw new SanitizeException(Outcome.REJECTED, "decode failed");
        } finally {
            reader.dispose();
        }
    }

    /** Draws the pixels into a fresh image, oriented for viewing (this also drops any odd colour model). */
    private static BufferedImage orient(BufferedImage decoded, int orientation) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        boolean swap = ExifOrientation.swapsSides(orientation);
        int type = decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB
                : decoded.getColorModel().getNumColorComponents() == 1 ? BufferedImage.TYPE_BYTE_GRAY
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage viewed = new BufferedImage(swap ? height : width, swap ? width : height, type);
        Graphics2D g = viewed.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(decoded, ExifOrientation.transform(orientation, width, height), null);
        } finally {
            g.dispose();
        }
        return viewed;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),
                    param);
            withoutJfif(metadata);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } catch (IOException e) {
            throw new IllegalStateException("couldn't encode the photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Drops the JFIF (APP0) marker the encoder would otherwise write: the output has no APPn at all. */
    private static void withoutJfif(IIOMetadata metadata) throws IOException {
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(JPEG_NATIVE);
        Node variety = root.getElementsByTagName("JPEGvariety").item(0);
        if (variety != null) {
            while (variety.hasChildNodes()) {
                variety.removeChild(variety.getFirstChild());
            }
        }
        metadata.setFromTree(JPEG_NATIVE, root);
    }

    private static byte[] writePng(BufferedImage image) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", bytes)) {
                throw new IllegalStateException("no png encoder");
            }
        } catch (IOException e) {
            throw new IllegalStateException("couldn't encode the photo", e);
        }
        return bytes.toByteArray();
    }
}

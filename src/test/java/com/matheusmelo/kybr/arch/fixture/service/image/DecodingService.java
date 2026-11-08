package com.matheusmelo.kybr.arch.fixture.service.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Violates the image isolation rule: an API-side class decoding pixels. */
public class DecodingService {

    public int width(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        return image.getWidth();
    }
}

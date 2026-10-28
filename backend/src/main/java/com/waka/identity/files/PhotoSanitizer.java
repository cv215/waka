package com.waka.identity.files;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Accepts JPEG or PNG recognized by their signature bytes, bounds dimensions before full decoding,
 * then re-encodes a plain JPEG: metadata such as GPS position is never kept.
 */
public final class PhotoSanitizer {
    public record Sanitized(byte[] jpeg, int width, int height) {
        @Override public String toString() { return "Sanitized[REDACTED]"; }
    }
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MIN_WIDTH = 320, MIN_HEIGHT = 240, MAX_SIDE = 8000;
    static final long MAX_PIXELS = 40_000_000L;
    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    private PhotoSanitizer() { }

    public static Sanitized sanitize(byte[] input, String declaredType) {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) throw invalid();
        String format;
        if ("image/jpeg".equals(declaredType) && startsWith(input, JPEG)) format = "jpeg";
        else if ("image/png".equals(declaredType) && startsWith(input, PNG)) format = "png";
        else throw invalid();
        BufferedImage decoded;
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReadersByFormatName(format);
            if (stream == null || !readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                // Not forward-only: counting the frames below needs to search the in-memory stream.
                reader.setInput(stream, false, true);
                if (reader.getNumImages(true) != 1) throw invalid();
                // Header dimensions are checked before any pixel is allocated.
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < MIN_WIDTH || height < MIN_HEIGHT || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) throw invalid();
                decoded = reader.read(0);
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException hidden) {
            if (hidden instanceof FileException known) throw known;
            throw invalid();
        }
        if (decoded == null) throw invalid();
        int width = decoded.getWidth(), height = decoded.getHeight();
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(decoded, 0, 0, null);
        } finally { graphics.dispose(); }
        return new Sanitized(encode(rgb), width, height);
    }

    private static byte[] encode(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            // No metadata object is passed: only the pixels are written.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException hidden) {
            throw new FileException(FileException.Code.SERVICE_UNAVAILABLE);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }
    private static FileException invalid() { return new FileException(FileException.Code.INVALID_FILE); }
}

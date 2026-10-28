package com.waka.identity.files;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhotoSanitizerTest {
    static byte[] image(String format, int width, int height, int type) throws Exception {
        BufferedImage image = new BufferedImage(width, height, type);
        var g = image.createGraphics();
        g.setColor(new Color(20, 120, 200, 128)); g.fillRect(0, 0, width / 2, height); g.dispose();
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    /**
     * Shapes the file like a phone photo: the JFIF APP0 segment is replaced by an APP1 "Exif" segment
     * (a minimal valid TIFF header with an empty directory) followed by a synthetic GPS marker text.
     */
    static byte[] withGpsSegment(byte[] jpeg) {
        assertEquals((byte) 0xE0, jpeg[3], "generated JPEG starts with JFIF APP0");
        int app0 = 2 + 2 + (((jpeg[4] & 0xff) << 8) | (jpeg[5] & 0xff));
        var tiff = new ByteArrayOutputStream();
        tiff.writeBytes("Exif".getBytes(StandardCharsets.US_ASCII)); tiff.write(0); tiff.write(0);
        tiff.writeBytes(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0 });
        tiff.writeBytes("GPSLatitude-SYNTHETIC-3.8480N-11.5021E".getBytes(StandardCharsets.US_ASCII));
        byte[] payload = tiff.toByteArray();
        int length = payload.length + 2;
        var out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8); out.write(0xFF); out.write(0xE1); out.write(length >> 8); out.write(length & 0xff);
        out.writeBytes(payload);
        out.write(jpeg, app0, jpeg.length - app0);
        return out.toByteArray();
    }

    @Test void reencodesJpegAndPngWithoutMetadata() throws Exception {
        byte[] jpeg = withGpsSegment(image("jpeg", 640, 480, BufferedImage.TYPE_INT_RGB));
        assertTrue(new String(jpeg, StandardCharsets.ISO_8859_1).contains("GPSLatitude"));
        var clean = PhotoSanitizer.sanitize(jpeg, "image/jpeg");
        String text = new String(clean.jpeg(), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("GPSLatitude"));
        assertFalse(text.contains("Exif"));
        assertEquals(640, clean.width()); assertEquals(480, clean.height());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(clean.jpeg())));

        var png = PhotoSanitizer.sanitize(image("png", 320, 240, BufferedImage.TYPE_INT_ARGB), "image/png");
        assertEquals((byte) 0xFF, png.jpeg()[0]); assertEquals((byte) 0xD8, png.jpeg()[1]);
        assertEquals("Sanitized[REDACTED]", png.toString());
    }

    @Test void refusesWrongSignaturesFakesAndBadSizes() throws Exception {
        byte[] jpeg = image("jpeg", 640, 480, BufferedImage.TYPE_INT_RGB);
        byte[] png = image("png", 640, 480, BufferedImage.TYPE_INT_RGB);
        assertRefused(jpeg, "image/png");
        assertRefused(png, "image/jpeg");
        assertRefused(jpeg, "image/gif");
        assertRefused(jpeg, null);
        assertRefused(new byte[0], "image/jpeg");
        assertRefused(null, "image/jpeg");
        byte[] fake = Arrays.copyOf(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }, 2000);
        assertRefused(fake, "image/jpeg");
        assertRefused("%PDF-1.7 not an image".getBytes(StandardCharsets.US_ASCII), "image/jpeg");
        assertRefused(Arrays.copyOf(png, png.length / 3), "image/png");
        assertRefused(image("png", 319, 240, BufferedImage.TYPE_INT_RGB), "image/png");
        assertRefused(image("png", 320, 239, BufferedImage.TYPE_INT_RGB), "image/png");
        assertRefused(image("png", 8001, 240, BufferedImage.TYPE_BYTE_BINARY), "image/png");
        assertRefused(Arrays.copyOf(jpeg, PhotoSanitizer.MAX_BYTES + 1), "image/jpeg");
    }

    private static void assertRefused(byte[] data, String type) {
        assertEquals(FileException.Code.INVALID_FILE,
                assertThrows(FileException.class, () -> PhotoSanitizer.sanitize(data, type)).code());
    }
}

package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;

class ImageUploadValidatorTest {
    private ObjectStorageProperties properties;
    private ImageUploadValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(1_024);
        properties.setMaxImagePixels(1_000);
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        validator = new ImageUploadValidator(properties, uploadPolicy);
    }

    @Test
    void detectsSupportedRasterFormatsFromBytes() {
        ValidatedImage png = validator.validate(source("card.png", "image/png", png(10, 20)));
        ValidatedImage jpeg = validator.validate(source("card.jpg", "image/jpeg", jpeg(10, 20)));
        ValidatedImage webp = validator.validate(source("card.webp", "image/webp", losslessWebp(10, 20)));

        assertEquals(new ValidatedImage("image/png", "png"), png);
        assertEquals(new ValidatedImage("image/jpeg", "jpg"), jpeg);
        assertEquals(new ValidatedImage("image/webp", "webp"), webp);
    }

    @Test
    void rejectsMismatchedDeclaredTypeAndMalformedPng() {
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> validator.validate(source("card.jpg", "image/jpeg", png(10, 20))));

        byte[] malformed = png(10, 20);
        byte[] wrongChunk = "FAIL".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(wrongChunk, 0, malformed, 12, wrongChunk.length);
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> validator.validate(source("card.png", "image/png", malformed)));
    }

    @Test
    void rejectsOversizedAndAnimatedImages() {
        assertThrows(
            BadRequestException.class,
            () -> validator.validate(source("card.png", "image/png", png(100, 11))));

        byte[] still = png(10, 20);
        byte[] animated = new byte[still.length + 4];
        System.arraycopy(still, 0, animated, 0, still.length);
        System.arraycopy("acTL".getBytes(StandardCharsets.US_ASCII), 0, animated, still.length, 4);
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> validator.validate(source("card.png", "image/png", animated)));
    }

    private static UploadSource source(String name, String contentType, byte[] bytes) {
        return UploadSource.from(name, contentType, bytes);
    }

    private static byte[] png(int width, int height) {
        byte[] bytes = new byte[33];
        byte[] signature = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        writeInt32(bytes, 8, 13);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        writeInt32(bytes, 16, width);
        writeInt32(bytes, 20, height);
        bytes[24] = 8;
        bytes[25] = 2;
        return bytes;
    }

    private static byte[] jpeg(int width, int height) {
        return new byte[] {
            (byte) 0xff, (byte) 0xd8,
            (byte) 0xff, (byte) 0xc0,
            0, 7,
            8,
            (byte) (height >>> 8), (byte) height,
            (byte) (width >>> 8), (byte) width,
            (byte) 0xff, (byte) 0xd9
        };
    }

    private static byte[] losslessWebp(int width, int height) {
        byte[] bytes = new byte[25];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        writeInt32LittleEndian(bytes, 4, bytes.length - 8);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy("VP8L".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        writeInt32LittleEndian(bytes, 16, 5);
        bytes[20] = 0x2f;
        int encodedWidth = width - 1;
        int encodedHeight = height - 1;
        bytes[21] = (byte) encodedWidth;
        bytes[22] = (byte) (((encodedHeight & 0x3) << 6) | ((encodedWidth >>> 8) & 0x3f));
        bytes[23] = (byte) (encodedHeight >>> 2);
        bytes[24] = (byte) (encodedHeight >>> 10);
        return bytes;
    }

    private static void writeInt32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static void writeInt32LittleEndian(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}

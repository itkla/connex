package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;

class ImageUploadValidatorTest {
    private static final byte[] VALID_WEBP = Base64.getDecoder().decode(
        "UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");
    private static final byte[] ANIMATED_WEBP = Base64.getDecoder().decode(
        "UklGRtgAAABXRUJQVlA4WAoAAAACAAAAAAAAAAAAQU5JTQYAAAAAAAAAAABBTk1GUgAAAAAAAAAAAAAAAAAAAGQAAABWUDggOgAAAPACAJ0BKgEAAQAARwiFhYiFhIgCAgJ1qgP4A/oCDU0YAP7+81h//mBm3Yo7/+YG//zQXhJbr//MvABBTk1GUgAAAAAAAAAAAAAAAAAAAGQAAABWUDggOgAAAPACAJ0BKgEAAQAARwiFhYiFhIgCAgJ1qgP4A/oCDU0YAP7+81h//mBm3Yo7/+YG//zQXhJbr//MvAA=");

    private ObjectStorageProperties properties;
    private ImageUploadValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(1_000_000);
        properties.setMaxImagePixels(1_000_000);
        validator = new ImageUploadValidator(properties, new UploadPolicy(properties));
    }

    @Test
    void fullyDecodesSupportedRasterFormatsAndPreservesBytes() throws IOException {
        byte[] pngBytes = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        byte[] jpegBytes = image("jpg", BufferedImage.TYPE_INT_RGB, 10, 20);

        ValidatedImage png = validator.validate(source("card.png", "image/png", pngBytes));
        ValidatedImage jpeg = validator.validate(source("card.jpg", "image/jpeg", jpegBytes));
        ValidatedImage webp = validator.validate(source("card.webp", "image/webp", VALID_WEBP));

        assertEquals("image/png", png.contentType());
        assertEquals("png", png.extension());
        assertArrayEquals(pngBytes, png.content());
        assertEquals("image/jpeg", jpeg.contentType());
        assertEquals("jpg", jpeg.extension());
        assertArrayEquals(jpegBytes, jpeg.content());
        assertEquals("image/webp", webp.contentType());
        assertEquals("webp", webp.extension());
        assertArrayEquals(VALID_WEBP, webp.content());
    }

    @Test
    void rejectsMismatchedDeclaredTypeAndMalformedRecognizedImage() throws IOException {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> validator.validate(source("card.jpg", "image/jpeg", png)));

        byte[] malformed = Arrays.copyOf(png, 24);
        assertThrows(
            BadRequestException.class,
            () -> validator.validate(source("card.png", "image/png", malformed)));
    }

    @Test
    void rejectsOversizedAndAnimatedImages() throws IOException {
        properties.setMaxImagePixels(100);
        byte[] oversized = image("png", BufferedImage.TYPE_INT_ARGB, 20, 20);
        assertThrows(
            BadRequestException.class,
            () -> validator.validate(source("card.png", "image/png", oversized)));

        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> validator.validate(source("card.webp", "image/webp", ANIMATED_WEBP)));
    }

    @Test
    void returnedBytesAreDefensiveCopies() throws IOException {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        ValidatedImage validated = validator.validate(source("card.png", "image/png", png));

        validated.content()[0] = 0;

        assertTrue(validated.content()[0] != 0);
    }

    private static UploadSource source(String name, String contentType, byte[] bytes) {
        return UploadSource.from(name, contentType, bytes);
    }

    private static byte[] image(String format, int type, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        image.setRGB(width / 2, height / 2, 0xff336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}

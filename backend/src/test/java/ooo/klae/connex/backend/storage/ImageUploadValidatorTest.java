package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
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
        validator = new ImageUploadValidator(
            properties,
            new UploadPolicy(properties),
            new ImageDecodeAdmissionService(properties));
    }

    @Test
    void fullyDecodesSupportedRasterFormatsAndEmitsCanonicalBytes() throws IOException {
        byte[] pngBytes = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        byte[] jpegBytes = image("jpg", BufferedImage.TYPE_INT_RGB, 10, 20);

        ValidatedImage png = validator.validate(source("card.png", "image/png", pngBytes));
        ValidatedImage jpeg = validator.validate(source("card.jpg", "image/jpeg", jpegBytes));
        ValidatedImage webp = validator.validate(source("card.webp", "image/webp", VALID_WEBP));

        assertEquals("image/png", png.contentType());
        assertEquals("png", png.extension());
        assertEquals("image/jpeg", jpeg.contentType());
        assertEquals("jpg", jpeg.extension());
        assertFalse(Arrays.equals(jpegBytes, jpeg.content()));
        assertTrue(Set.of("image/jpeg", "image/png").contains(webp.contentType()));
        assertTrue(Set.of("jpg", "png").contains(webp.extension()));
        assertFalse(Arrays.equals(VALID_WEBP, webp.content()));
        assertEquals(10, ImageIO.read(new ByteArrayInputStream(png.content())).getWidth());
        assertEquals(10, ImageIO.read(new ByteArrayInputStream(jpeg.content())).getWidth());
        assertEquals(1, ImageIO.read(new ByteArrayInputStream(webp.content())).getWidth());
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
    void rejectsHighBitDepthBeforeDecode() throws IOException {
        byte[] highDepth = image("png", BufferedImage.TYPE_USHORT_GRAY, 20, 20);

        assertThrows(
            BadRequestException.class,
            () -> validator.validate(source("portrait.png", "image/png", highDepth)));
    }

    @Test
    void rejectsCanonicalEncodingAtTheConfiguredCeiling() throws IOException {
        byte[] jpeg = image("jpg", BufferedImage.TYPE_INT_RGB, 240, 140);
        properties.setMaxUploadBytes(jpeg.length);

        assertThrows(RequestBodyTooLargeException.class,
            () -> validator.validate(source("card.jpg", "image/jpeg", jpeg)));
    }

    @Test
    void removesMetadataAndTrailingPayload() throws IOException {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 20, 20);
        byte[] marker = "private-gps-marker".getBytes(StandardCharsets.US_ASCII);
        byte[] tainted = Arrays.copyOf(png, png.length + marker.length);
        System.arraycopy(marker, 0, tainted, png.length, marker.length);

        ValidatedImage validated = validator.validate(
            source("portrait.png", "image/png", tainted));

        assertFalse(contains(validated.content(), marker));
        assertEquals(20, ImageIO.read(
            new ByteArrayInputStream(validated.content())).getWidth());
    }

    @Test
    void returnedBytesAreDefensiveCopies() throws IOException {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        ValidatedImage validated = validator.validate(source("card.png", "image/png", png));

        validated.content()[0] = 0;

        assertTrue(validated.content()[0] != 0);
    }

    @Test
    void rejectsExcessWorkBeforeOpeningOrAllocatingTheUpload() throws Exception {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 10, 20);
        properties.setMaxConcurrentImageDecodes(1);
        validator = new ImageUploadValidator(
            properties,
            new UploadPolicy(properties),
            new ImageDecodeAdmissionService(properties));
        CountDownLatch firstOpened = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        UploadSource blocking = new UploadSource("first.png", "image/png", png.length, () -> {
            firstOpened.countDown();
            try {
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("test interrupted", exception);
            }
            return new ByteArrayInputStream(png);
        });
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = Thread.startVirtualThread(() -> {
            try {
                validator.validate(blocking);
            } catch (Throwable exception) {
                firstFailure.set(exception);
            }
        });
        assertTrue(firstOpened.await(5, TimeUnit.SECONDS));
        AtomicBoolean secondOpened = new AtomicBoolean();
        UploadSource second = new UploadSource("second.png", "image/png", png.length, () -> {
            secondOpened.set(true);
            return new ByteArrayInputStream(png);
        });

        try {
            assertThrows(ServiceUnavailableException.class, () -> validator.validate(second));
            assertFalse(secondOpened.get());
        } finally {
            releaseFirst.countDown();
            first.join(5_000);
        }

        assertFalse(first.isAlive());
        assertTrue(firstFailure.get() == null);
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

    private static boolean contains(byte[] content, byte[] candidate) {
        for (int offset = 0; offset <= content.length - candidate.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < candidate.length; index++) {
                if (content[offset + index] != candidate[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}

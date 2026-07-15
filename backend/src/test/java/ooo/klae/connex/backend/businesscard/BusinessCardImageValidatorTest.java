package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.exceptions.UnprocessableBusinessCardException;
import ooo.klae.connex.backend.exceptions.UnsupportedBusinessCardMediaTypeException;

class BusinessCardImageValidatorTest {
    private static final byte[] VALID_WEBP = Base64.getDecoder().decode(
            "UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");
    private static final byte[] ANIMATED_WEBP = Base64.getDecoder().decode(
            "UklGRtgAAABXRUJQVlA4WAoAAAACAAAAAAAAAAAAQU5JTQYAAAAAAAAAAABBTk1GUgAAAAAAAAAAAAAAAAAAAGQAAABWUDggOgAAAPACAJ0BKgEAAQAARwiFhYiFhIgCAgJ1qgP4A/oCDU0YAP7+81h//mBm3Yo7/+YG//zQXhJbr//MvABBTk1GUgAAAAAAAAAAAAAAAAAAAGQAAABWUDggOgAAAPACAJ0BKgEAAQAARwiFhYiFhIgCAgJ1qgP4A/oCDU0YAP7+81h//mBm3Yo7/+YG//zQXhJbr//MvAA=");

    private BusinessCardProperties properties;
    private BusinessCardImageValidator validator;

    @BeforeEach
    void setUp() {
        properties = new BusinessCardProperties();
        properties.setMaxImageBytes(1_000_000);
        properties.setMaxWidth(1_000);
        properties.setMaxHeight(1_000);
        properties.setMaxPixels(1_000_000);
        validator = new BusinessCardImageValidator(properties);
    }

    @Test
    void acceptsFullyDecodedPngAndPreservesOriginalBytes() throws IOException {
        byte[] content = image("png", BufferedImage.TYPE_INT_ARGB, 240, 140);

        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.png", "image/png", content));

        assertEquals("image/png", validated.contentType());
        assertEquals("png", validated.extension());
        assertEquals(240, validated.width());
        assertEquals(140, validated.height());
        assertTrue(java.util.Arrays.equals(content, validated.content()));
    }

    @Test
    void acceptsFullyDecodedJpegWithGenericDeclaredType() throws IOException {
        byte[] content = image("jpg", BufferedImage.TYPE_INT_RGB, 240, 140);

        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.bin", "application/octet-stream", content));

        assertEquals("image/jpeg", validated.contentType());
        assertEquals("jpg", validated.extension());
    }

    @Test
    void acceptsFullyDecodedWebp() {
        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.webp", "image/webp", VALID_WEBP));

        assertEquals("image/webp", validated.contentType());
        assertEquals("webp", validated.extension());
        assertEquals(1, validated.width());
        assertEquals(1, validated.height());
    }

    @Test
    void rejectsAnimatedWebp() {
        assertThrows(UnprocessableBusinessCardException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.webp", "image/webp", ANIMATED_WEBP)));
    }

    @Test
    void rejectsMalformedWebp() {
        byte[] malformed = java.util.Arrays.copyOf(VALID_WEBP, 20);

        assertThrows(UnprocessableBusinessCardException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.webp", "image/webp", malformed)));
    }

    @Test
    void rejectsDeclaredMediaTypeMismatch() throws IOException {
        byte[] content = image("png", BufferedImage.TYPE_INT_ARGB, 240, 140);

        assertThrows(UnsupportedBusinessCardMediaTypeException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.jpg", "image/jpeg", content)));
    }

    @Test
    void rejectsMalformedRecognizedSignature() {
        byte[] malformed = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x01};

        assertThrows(UnprocessableBusinessCardException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.jpg", "image/jpeg", malformed)));
    }

    @Test
    void rejectsPixelBombBeforeFullDecode() throws IOException {
        properties.setMaxPixels(100);
        byte[] content = image("png", BufferedImage.TYPE_INT_ARGB, 20, 20);

        assertThrows(UnprocessableBusinessCardException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.png", "image/png", content)));
    }

    @Test
    void rejectsOversizedUploadFromMultipartMetadata() {
        properties.setMaxImageBytes(3);

        assertThrows(RequestBodyTooLargeException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.png", "image/png", new byte[4])));
    }

    @Test
    void rejectsOverlappingImageDecodes() throws Exception {
        byte[] content = image("png", BufferedImage.TYPE_INT_ARGB, 240, 140);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        MockMultipartFile blocking = new MockMultipartFile(
                "image", "card.png", "image/png", content) {
            @Override
            public byte[] getBytes() throws IOException {
                readStarted.countDown();
                try {
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test interrupted", exception);
                }
                return super.getBytes();
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = Thread.startVirtualThread(() -> {
            try {
                validator.validate(blocking);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));

        try {
            assertThrows(TooManyRequestsException.class, () -> validator.validate(
                    new MockMultipartFile("image", "card.png", "image/png", content)));
        } finally {
            releaseRead.countDown();
            first.join(5_000);
        }

        assertTrue(!first.isAlive());
        assertNull(failure.get());
    }

    private static byte[] image(String format, int type, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        image.setRGB(width / 2, height / 2, 0xff336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}

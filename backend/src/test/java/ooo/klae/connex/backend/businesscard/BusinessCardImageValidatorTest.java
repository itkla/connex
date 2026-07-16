package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.exceptions.UnprocessableBusinessCardException;
import ooo.klae.connex.backend.exceptions.UnsupportedBusinessCardMediaTypeException;
import ooo.klae.connex.backend.storage.ImageDecodeAdmissionService;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;

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
        ObjectStorageProperties storageProperties = new ObjectStorageProperties();
        storageProperties.setMaxConcurrentImageDecodes(1);
        validator = new BusinessCardImageValidator(
                properties, new ImageDecodeAdmissionService(storageProperties));
    }

    @Test
    void acceptsFullyDecodedPngAndEmitsSanitizedJpeg() throws IOException {
        byte[] content = image("png", BufferedImage.TYPE_INT_ARGB, 240, 140);

        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.png", "image/png", content));

        assertEquals("image/jpeg", validated.contentType());
        assertEquals("jpg", validated.extension());
        assertEquals(240, validated.width());
        assertEquals(140, validated.height());
        assertTrue(!java.util.Arrays.equals(content, validated.content()));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(validated.content()));
        assertEquals(240, decoded.getWidth());
        assertEquals(140, decoded.getHeight());
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

        assertEquals("image/jpeg", validated.contentType());
        assertEquals("jpg", validated.extension());
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
    void rejectsHighBitDepthBeforeFullDecode() throws IOException {
        byte[] content = image("png", BufferedImage.TYPE_USHORT_GRAY, 20, 20);

        assertThrows(UnprocessableBusinessCardException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.png", "image/png", content)));
    }

    @Test
    void ignoresCompressedPngTextMetadata() throws IOException {
        byte[] content = compressedTextPng(512);

        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.png", "image/png", content));

        assertEquals(20, validated.width());
        assertEquals(20, validated.height());
    }

    @Test
    void rejectsOversizedUploadFromMultipartMetadata() {
        properties.setMaxImageBytes(3);

        assertThrows(RequestBodyTooLargeException.class,
                () -> validator.validate(new MockMultipartFile(
                        "image", "card.png", "image/png", new byte[4])));
    }

    @Test
    void appliesExifOrientationAndRemovesMetadata() throws IOException {
        properties.setMaxWidth(30);
        properties.setMaxHeight(50);
        byte[] content = orientedJpeg();

        ValidatedBusinessCardImage validated = validator.validate(
                new MockMultipartFile("image", "card.jpg", "image/jpeg", content));

        assertEquals(20, validated.width());
        assertEquals(40, validated.height());
        assertTrue(!contains(validated.content(), "Exif".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(validated.content()));
        int top = decoded.getRGB(decoded.getWidth() / 2, 5);
        int bottom = decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() - 6);
        assertTrue(((top >> 16) & 0xff) > (top & 0xff));
        assertTrue((bottom & 0xff) > ((bottom >> 16) & 0xff));
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

    private static byte[] orientedJpeg() throws IOException {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < image.getWidth() / 2 ? 0xffff0000 : 0xff0000ff);
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", encoded));
        byte[] jpeg = encoded.toByteArray();
        byte[] exif = {
            (byte) 0xff, (byte) 0xe1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0x00, 0x00,
            'I', 'I', 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + exif.length);
        output.write(jpeg, 0, 2);
        output.write(exif);
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }

    private static byte[] compressedTextPng(int chunkCount) throws IOException {
        byte[] png = image("png", BufferedImage.TYPE_INT_ARGB, 20, 20);
        int iendOffset = png.length - 12;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, iendOffset);
        byte[] expanded = new byte[256 * 1024];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(expanded);
        }
        byte[] keyword = "metadata".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream chunkData = new ByteArrayOutputStream();
        chunkData.write(keyword);
        chunkData.write(0);
        chunkData.write(0);
        chunkData.write(compressed.toByteArray());
        byte[] data = chunkData.toByteArray();
        for (int index = 0; index < chunkCount; index++) {
            writePngChunk(output, "zTXt", data);
        }
        output.write(png, iendOffset, 12);
        return output.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream output, String type, byte[] data)
            throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 checksum = new CRC32();
        checksum.update(typeBytes);
        checksum.update(data);
        DataOutputStream encoded = new DataOutputStream(output);
        encoded.writeInt(data.length);
        encoded.write(typeBytes);
        encoded.write(data);
        encoded.writeInt((int) checksum.getValue());
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

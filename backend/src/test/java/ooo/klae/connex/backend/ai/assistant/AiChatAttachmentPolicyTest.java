package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.BoundedImageValidationExecutor;
import ooo.klae.connex.backend.storage.ImageDecodeAdmissionService;
import ooo.klae.connex.backend.storage.ImageUploadValidator;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.UploadContentInspector;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadSource;
import tools.jackson.databind.ObjectMapper;

class AiChatAttachmentPolicyTest {
    private AiChatAttachmentPolicy policy;
    private UploadContentInspector inspector;
    private BoundedImageValidationExecutor imageValidationExecutor;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(25L * 1024L * 1024L);
        UploadPolicy uploadPolicy = new UploadPolicy(properties);
        imageValidationExecutor = new BoundedImageValidationExecutor();
        ImageUploadValidator imageUploadValidator = new ImageUploadValidator(
            properties,
            uploadPolicy,
            new ImageDecodeAdmissionService(properties),
            imageValidationExecutor);
        inspector = new UploadContentInspector(
            uploadPolicy, imageUploadValidator, new ObjectMapper());
        policy = new AiChatAttachmentPolicy(
                uploadPolicy,
                inspector,
                imageUploadValidator);
    }

    @AfterEach
    void tearDown() {
        inspector.close();
        imageValidationExecutor.close();
    }

    @Test
    void acceptsOnlyStrictUtf8TextAtTheAssistantBoundary() throws Exception {
        byte[] content = "name,email\nAda,ada@example.com".getBytes(StandardCharsets.UTF_8);

        InspectedUpload prepared = policy.prepare(
                UploadSource.from("contacts.csv", "text/csv", content));

        assertEquals("text/csv", prepared.contentType());
        assertArrayEquals(content, prepared.content());
    }

    @Test
    void carriesOneCanonicalAiImageArtifactThroughStoredRead() throws Exception {
        byte[] source = png();

        InspectedUpload prepared = policy.prepare(
            UploadSource.from("screenshot.png", "image/png", source));
        byte[] canonical = prepared.content();

        assertEquals(UploadFormat.JPEG, prepared.format());
        assertEquals("image/jpeg", prepared.contentType());
        assertArrayEquals(
            canonical,
            policy.readImage(
                prepared.fileName(),
                new ByteArrayInputStream(canonical),
                canonical.length).content());
    }

    @Test
    void rejectsOversizedTextBeforeExtraction() {
        byte[] content = new byte[AiChatAttachmentPolicy.MAX_TEXT_BYTES + 1];

        assertThrows(RequestBodyTooLargeException.class, () -> policy.prepare(
                UploadSource.from("notes.txt", "text/plain", content)));
    }

    @Test
    void rejectsDisallowedAndMismatchedDocumentTypes() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        assertThrows(UnsupportedUploadMediaTypeException.class, () -> policy.prepare(
                UploadSource.from("brief.pdf", "application/pdf", content)));
        assertThrows(UnsupportedUploadMediaTypeException.class, () -> policy.prepare(
                UploadSource.from("contacts.json", "text/plain", content)));
    }

    @Test
    void rejectsMalformedUtf8AtUploadAndStoredReadBoundaries() {
        byte[] malformed = {(byte) 0xc3, 0x28};

        assertThrows(UnsupportedUploadMediaTypeException.class, () -> policy.prepare(
                UploadSource.from("notes.md", "text/markdown", malformed)));
        assertThrows(BadRequestException.class, () -> policy.readText(
                new ByteArrayInputStream(malformed), malformed.length));
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color(x * 3 % 256, y * 5 % 256, (x + y) * 7 % 256).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

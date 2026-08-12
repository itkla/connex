package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageUploadValidator;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadSource;

class AiChatAttachmentPolicyTest {
    private AiChatAttachmentPolicy policy;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(25L * 1024L * 1024L);
        policy = new AiChatAttachmentPolicy(
                new UploadPolicy(properties),
                mock(ImageUploadValidator.class));
    }

    @Test
    void acceptsOnlyStrictUtf8TextAtTheAssistantBoundary() throws Exception {
        byte[] content = "name,email\nAda,ada@example.com".getBytes(StandardCharsets.UTF_8);

        UploadSource prepared = policy.prepare(
                UploadSource.from("contacts.csv", "text/csv", content));

        assertEquals("text/csv", prepared.contentType());
        try (var input = prepared.openStream()) {
            assertArrayEquals(content, input.readAllBytes());
        }
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

        assertThrows(BadRequestException.class, () -> policy.prepare(
                UploadSource.from("notes.md", "text/markdown", malformed)));
        assertThrows(BadRequestException.class, () -> policy.readText(
                new ByteArrayInputStream(malformed), malformed.length));
    }
}

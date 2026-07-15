package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;

class UploadPolicyTest {
    private UploadPolicy policy;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(8);
        policy = new UploadPolicy(properties);
    }

    @Test
    void sanitizesUntrustedMetadata() {
        ValidatedUpload upload = policy.validateGeneric(
            UploadSource.from("../../report\u202E.pdf", "Application/PDF; charset=binary", new byte[] { 1 }));

        assertEquals("report_.pdf", upload.fileName());
        assertEquals("application/pdf", upload.contentType());
        assertEquals("pdf", upload.extension());
    }

    @Test
    void rejectsRenderableTypesEvenWhenOnlyOneMetadataSignalIsUnsafe() {
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> policy.validateGeneric(
                UploadSource.from("page.html", "application/octet-stream", new byte[] { 1 })));
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> policy.validateGeneric(
                UploadSource.from("page.bin", "image/svg+xml", new byte[] { 1 })));
    }

    @Test
    void rejectsConfiguredOversize() {
        assertThrows(
            RequestBodyTooLargeException.class,
            () -> policy.validateGeneric(
                UploadSource.from("large.pdf", "application/pdf", new byte[9])));
    }

    @Test
    void defaultPolicyAllowsGenericAttachmentAboveScannerLimit() {
        ObjectStorageProperties defaults = new ObjectStorageProperties();
        UploadPolicy defaultPolicy = new UploadPolicy(defaults);
        byte[] attachment = new byte[8 * 1024 * 1024 + 1];

        assertDoesNotThrow(() -> defaultPolicy.validateGeneric(
            UploadSource.from("large.pdf", "application/pdf", attachment)));
    }
}

package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.storage.ObjectStorageProperties.Provider;

class ObjectStorageBackendIdentityTest {
    @Test
    void normalizesFilesystemRootToAnAbsoluteLexicalIdentity() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setFilesystemRoot("storage/../private-objects");

        ObjectStorageBackendIdentity identity =
            ObjectStorageBackendIdentity.configured(properties);

        assertEquals(Provider.FILESYSTEM, identity.provider());
        assertEquals(
            Path.of("private-objects").toAbsolutePath().normalize().toString(),
            identity.filesystemRoot());
        assertNull(identity.s3Bucket());
    }

    @Test
    void normalizesEquivalentS3Coordinates() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setProvider(Provider.S3);
        properties.getS3().setBucket("  PRIVATE-CARDS  ");
        properties.getS3().setRegion(" AP-NORTHEAST-1 ");
        properties.getS3().setEndpoint("HTTPS://EXAMPLE.TEST:443/storage/../objects/");
        properties.getS3().setPathStyle(true);

        ObjectStorageBackendIdentity identity =
            ObjectStorageBackendIdentity.configured(properties);

        assertEquals(Provider.S3, identity.provider());
        assertEquals("private-cards", identity.s3Bucket());
        assertEquals("ap-northeast-1", identity.s3Region());
        assertEquals("https://example.test/objects", identity.s3Endpoint());
        assertEquals(true, identity.s3PathStyle());
        assertNull(identity.filesystemRoot());
    }
}

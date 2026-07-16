package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.storage.ObjectStorageBackendIdentity;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.Provider;

class ObjectStorageBackendIdentityMapperTest extends AbstractMapperTest {
    @Autowired ObjectStorageBackendIdentityMapper identityMapper;
    @Autowired ObjectStorageProperties properties;

    @Test
    void singletonPreservesTheFirstNormalizedBackendIdentity() {
        ObjectStorageBackendIdentity expected =
            ObjectStorageBackendIdentity.configured(properties);
        ObjectStorageBackendIdentity conflicting = new ObjectStorageBackendIdentity(
            Provider.FILESYSTEM,
            expected.filesystemRoot() + "-different",
            null,
            null,
            null,
            null);

        assertEquals(expected, identityMapper.find());
        assertEquals(0, identityMapper.insertIfAbsent(conflicting));
        assertEquals(expected, identityMapper.find());
    }
}

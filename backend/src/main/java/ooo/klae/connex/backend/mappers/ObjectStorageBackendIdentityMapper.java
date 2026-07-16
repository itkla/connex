package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.storage.ObjectStorageBackendIdentity;

/**
 * Control-plane persistence for the installation's immutable object-storage coordinates.
 */
public interface ObjectStorageBackendIdentityMapper {
    int insertIfAbsent(ObjectStorageBackendIdentity identity);

    ObjectStorageBackendIdentity find();
}

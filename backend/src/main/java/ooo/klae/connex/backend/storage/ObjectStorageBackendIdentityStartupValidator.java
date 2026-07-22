package ooo.klae.connex.backend.storage;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.ObjectStorageBackendIdentityMapper;

/**
 * Persists the first configured object-storage coordinates and aborts every mismatched startup.
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ObjectStorageBackendIdentityStartupValidator implements ApplicationRunner {
    private final ObjectStorageProperties properties;
    private final ObjectStorageBackendIdentityMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        ObjectStorageBackendIdentity configured = ObjectStorageBackendIdentity.configured(properties);
        int inserted = mapper.insertIfAbsent(configured);
        if (inserted < 0 || inserted > 1) {
            throw new IllegalStateException("Object-storage backend identity could not be persisted safely");
        }
        ObjectStorageBackendIdentity persisted = mapper.find();
        if (persisted == null || !persisted.equals(configured)) {
            throw new IllegalStateException(
                "Configured object-storage backend identity does not match the persisted installation identity");
        }
    }
}

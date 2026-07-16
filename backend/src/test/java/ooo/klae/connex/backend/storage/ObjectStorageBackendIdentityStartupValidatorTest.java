package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.annotation.Order;

import ooo.klae.connex.backend.mappers.ObjectStorageBackendIdentityMapper;

@ExtendWith(MockitoExtension.class)
class ObjectStorageBackendIdentityStartupValidatorTest {
    @Mock ObjectStorageBackendIdentityMapper mapper;

    private ObjectStorageProperties properties;
    private ObjectStorageBackendIdentityStartupValidator validator;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setFilesystemRoot("/srv/connex/objects");
        validator = new ObjectStorageBackendIdentityStartupValidator(properties, mapper);
    }

    @Test
    void firstStartupPersistsThenVerifiesTheConfiguredIdentity() {
        ObjectStorageBackendIdentity identity =
            ObjectStorageBackendIdentity.configured(properties);
        when(mapper.insertIfAbsent(identity)).thenReturn(1);
        when(mapper.find()).thenReturn(identity);

        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));

        InOrder order = inOrder(mapper);
        order.verify(mapper).insertIfAbsent(identity);
        order.verify(mapper).find();
    }

    @Test
    void concurrentFirstInsertWinnerWithTheSameIdentityIsAccepted() {
        ObjectStorageBackendIdentity identity =
            ObjectStorageBackendIdentity.configured(properties);
        when(mapper.insertIfAbsent(identity)).thenReturn(0);
        when(mapper.find()).thenReturn(identity);

        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));
    }

    @Test
    void startupAbortsWhenThePersistedIdentityDiffers() {
        ObjectStorageBackendIdentity configured =
            ObjectStorageBackendIdentity.configured(properties);
        ObjectStorageBackendIdentity persisted = new ObjectStorageBackendIdentity(
            ObjectStorageProperties.Provider.FILESYSTEM,
            "/srv/connex/other-objects",
            null,
            null,
            null,
            null);
        when(mapper.insertIfAbsent(configured)).thenReturn(0);
        when(mapper.find()).thenReturn(persisted);

        assertThrows(IllegalStateException.class,
            () -> validator.run(new DefaultApplicationArguments()));
    }

    @Test
    void startupAbortsWhenTheSingletonCannotBeReloaded() {
        ObjectStorageBackendIdentity configured =
            ObjectStorageBackendIdentity.configured(properties);
        when(mapper.insertIfAbsent(configured)).thenReturn(0);
        when(mapper.find()).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> validator.run(new DefaultApplicationArguments()));
    }

    @Test
    void identityValidationPrecedesStorageMaintenanceAndReadinessProbing() {
        int identityOrder = order(ObjectStorageBackendIdentityStartupValidator.class);
        int filesystemOrder = order(FilesystemObjectStorage.class);
        int managedServiceOrder = order(ManagedObjectService.class);

        assertTrue(identityOrder < filesystemOrder);
        assertTrue(filesystemOrder < managedServiceOrder);
    }

    private static int order(Class<?> type) {
        return type.getAnnotation(Order.class).value();
    }
}

package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;

class MaintenanceModeStartupValidatorTest {
    @Test
    void acceptsNormalServerOnlyWhenMigrationIsOff() {
        ObjectStorageProperties properties = new ObjectStorageProperties();

        assertDoesNotThrow(() -> MaintenanceModeStartupValidator.validate(
                new MockEnvironment(), properties));

        properties.getLegacyMigration().setMode(LegacyMigrationMode.DRY_RUN);
        assertThrows(IllegalStateException.class,
                () -> MaintenanceModeStartupValidator.validate(new MockEnvironment(), properties));
    }

    @Test
    void rejectsUnknownOrIncompleteMaintenanceMode() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        MockEnvironment unknown = new MockEnvironment()
                .withProperty("connex.maintenance.mode", "legacy-upload-migraton");
        MockEnvironment incomplete = new MockEnvironment()
                .withProperty("connex.maintenance.mode", "legacy-upload-migration")
                .withProperty("spring.main.web-application-type", "none");

        assertThrows(IllegalStateException.class,
                () -> MaintenanceModeStartupValidator.validate(unknown, properties));
        assertThrows(IllegalStateException.class,
                () -> MaintenanceModeStartupValidator.validate(incomplete, properties));
    }

    @Test
    void rejectsMaintenanceModeInWebApplication() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getLegacyMigration().setMode(LegacyMigrationMode.DRY_RUN);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("connex.maintenance.mode", "legacy-upload-migration")
                .withProperty("spring.main.web-application-type", "servlet");

        assertThrows(IllegalStateException.class,
                () -> MaintenanceModeStartupValidator.validate(environment, properties));
    }

    @Test
    void acceptsMatchingNonWebMigrationMode() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getLegacyMigration().setMode(LegacyMigrationMode.DRY_RUN);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("connex.maintenance.mode", "legacy-upload-migration")
                .withProperty("spring.main.web-application-type", "NONE");

        assertDoesNotThrow(() -> MaintenanceModeStartupValidator.validate(environment, properties));
    }

    @Test
    void rejectsMaintenanceModeWhitespaceAndCaseVariants() {
        ObjectStorageProperties properties = new ObjectStorageProperties();

        for (String mode : new String[] {" off ", "OFF", " legacy-upload-migration "}) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("connex.maintenance.mode", mode);
            assertThrows(IllegalStateException.class,
                    () -> MaintenanceModeStartupValidator.validate(environment, properties));
        }
    }
}

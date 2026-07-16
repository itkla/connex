package ooo.klae.connex.backend.config;

import java.util.Locale;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;

/** Fails startup unless maintenance mode selects one complete, isolated task. */
@Component
public class MaintenanceModeStartupValidator {
    private static final String OFF = "off";
    private static final String LEGACY_UPLOAD_MIGRATION = "legacy-upload-migration";

    public MaintenanceModeStartupValidator(
            Environment environment,
            ObjectStorageProperties objectStorageProperties) {
        validate(environment, objectStorageProperties);
    }

    static void validate(Environment environment, ObjectStorageProperties objectStorageProperties) {
        String maintenanceMode = environment.getProperty("connex.maintenance.mode", OFF);
        LegacyMigrationMode migrationMode = objectStorageProperties.getLegacyMigration().getMode();
        if (OFF.equals(maintenanceMode)) {
            if (migrationMode != LegacyMigrationMode.OFF) {
                throw new IllegalStateException(
                        "Legacy upload migration mode requires maintenance mode legacy-upload-migration");
            }
            return;
        }
        if (!LEGACY_UPLOAD_MIGRATION.equals(maintenanceMode)) {
            throw new IllegalStateException("Unknown maintenance mode: " + maintenanceMode);
        }
        if (migrationMode == LegacyMigrationMode.OFF) {
            throw new IllegalStateException(
                    "Legacy upload maintenance mode requires DRY_RUN or MIGRATE migration mode");
        }
        String webApplicationType = normalized(environment.getProperty(
                "spring.main.web-application-type", ""));
        if (!"none".equals(webApplicationType)) {
            throw new IllegalStateException(
                    "Legacy upload maintenance mode requires spring.main.web-application-type=none");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

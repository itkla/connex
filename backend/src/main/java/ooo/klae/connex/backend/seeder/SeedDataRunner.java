package ooo.klae.connex.backend.seeder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes an explicit one-shot deterministic seed invocation.
 *
 * <p>Local example from {@code backend/}:
 * {@code CONNEX_DB_URL='jdbc:mysql://127.0.0.1:3313/connex_seeder?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&sslMode=DISABLED'
 * CONNEX_DB_USERNAME=connexuser CONNEX_DB_PASSWORD=connexpass bash gradlew seedData
 * -PseederProfile=small -PseederSeed=853 -PseederWorkspaces=1
 * -PseederAnchorDate=2026-01-15}.
 *
 * <p>CI supplies a dedicated disposable {@code CONNEX_DB_URL} plus its
 * {@code CONNEX_DB_USERNAME} and {@code CONNEX_DB_PASSWORD} variables:
 * {@code bash gradlew seedData -PseederProfile=small -PseederSeed=853
 * -PseederWorkspaces=1 -PseederAnchorDate=2026-01-15 --no-daemon}.
 *
 * <p>Seeded users authenticate with plaintext {@code seeder-password}; the persisted
 * BCrypt hash is a precomputed constant. Flyway's installed-rank timing report is logged
 * immediately after migration and before mapper seeding begins.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seeder")
@ConditionalOnProperty(prefix = "connex.seeder", name = "enabled", havingValue = "true")
public class SeedDataRunner implements ApplicationRunner {

    private final SeederProperties properties;
    private final SeederGuard guard;
    private final SeederService seederService;
    private final Clock clock;
    private final Flyway flyway;

    @Override
    public void run(ApplicationArguments args) {
        guard.verify();
        verifyCurrentMigrationState();
        LocalDate anchorDate = properties.getAnchorDate() == null
            ? LocalDate.now(clock)
            : properties.getAnchorDate();
        log.info(
            "Starting deterministic seed profile={} seed={} workspaces={} anchorDate={}",
            properties.getProfile(),
            properties.getSeed(),
            properties.getWorkspaces(),
            anchorDate
        );
        SeedRunSummary summary = seederService.seed(
            properties.getProfile(),
            properties.getSeed(),
            properties.getWorkspaces(),
            anchorDate
        );
        for (SeedRunSummary.WorkspaceSummary workspace : summary.workspaces()) {
            log.info(
                "Seeder summary workspace={} slug={} rowCounts={}",
                workspace.ordinal(),
                workspace.slug(),
                workspace.rowCounts()
            );
        }
        log.info(
            "Deterministic seed completed profile={} seed={} workspaces={} anchorDate={}",
            summary.profile(),
            summary.seed(),
            summary.workspaces().size(),
            summary.anchorDate()
        );
    }

    private void verifyCurrentMigrationState() {
        try {
            MigrationInfoService migrationInfo = flyway.info();
            MigrationInfo currentMigration = migrationInfo.current();
            MigrationVersion currentVersion = currentMigration == null
                ? null
                : currentMigration.getVersion();
            MigrationVersion latestVersion = Arrays.stream(migrationInfo.all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .orElse(null);
            if (migrationInfo.pending().length != 0
                    || currentVersion == null
                    || latestVersion == null
                    || !currentVersion.equals(latestVersion)) {
                throw SeederStartupConfigurationValidator.refused(
                    "Flyway has not applied the complete current migration set"
                );
            }
        } catch (RuntimeException exception) {
            throw SeederStartupConfigurationValidator.cleanRefusal(
                exception,
                "could not verify current Flyway migration state"
            );
        }
    }
}

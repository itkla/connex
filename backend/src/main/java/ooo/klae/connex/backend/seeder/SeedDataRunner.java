package ooo.klae.connex.backend.seeder;

import java.time.Clock;
import java.time.LocalDate;

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
 * <p>CI uses the backend job's existing {@code CONNEX_DB_URL},
 * {@code CONNEX_DB_USERNAME}, and {@code CONNEX_DB_PASSWORD} variables:
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

    @Override
    public void run(ApplicationArguments args) {
        guard.verify();
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
}

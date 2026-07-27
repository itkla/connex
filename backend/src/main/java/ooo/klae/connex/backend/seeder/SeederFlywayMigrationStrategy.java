package ooo.klae.connex.backend.seeder;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import lombok.RequiredArgsConstructor;

/**
 * Places the production guard before Flyway's first schema write for seeder runs.
 */
@RequiredArgsConstructor
public class SeederFlywayMigrationStrategy implements FlywayMigrationStrategy {

    private final SeederGuard guard;
    private final MigrationTimingReporter migrationTimingReporter;

    @Override
    public void migrate(Flyway flyway) {
        guard.verify(flyway.getConfiguration().getDataSource());
        flyway.migrate();
        migrationTimingReporter.report(flyway);
    }
}

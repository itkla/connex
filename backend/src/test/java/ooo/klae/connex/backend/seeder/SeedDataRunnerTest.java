package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class SeedDataRunnerTest {

    @Test
    void refusesPendingMigrationBeforeSeeding() {
        MigrationInfo current = migration("126");
        MigrationInfo pending = migration("127");
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        when(migrationInfo.current()).thenReturn(current);
        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current, pending});
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[] {pending});

        assertMigrationRefusal(
            migrationInfo,
            "Seeder refused: Flyway has not applied the complete current migration set"
        );
    }

    @Test
    void refusesMissingCurrentMigrationBeforeSeeding() {
        MigrationInfo latest = migration("126");
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        when(migrationInfo.current()).thenReturn(null);
        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {latest});
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[0]);

        assertMigrationRefusal(
            migrationInfo,
            "Seeder refused: Flyway has not applied the complete current migration set"
        );
    }

    @Test
    void refusesMissingLatestMigrationBeforeSeeding() {
        MigrationInfo current = migration("126");
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        when(migrationInfo.current()).thenReturn(current);
        when(migrationInfo.all()).thenReturn(new MigrationInfo[0]);
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[0]);

        assertMigrationRefusal(
            migrationInfo,
            "Seeder refused: Flyway has not applied the complete current migration set"
        );
    }

    @Test
    void refusesVersionMismatchBeforeSeeding() {
        MigrationInfo current = migration("126");
        MigrationInfo latest = migration("127");
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        when(migrationInfo.current()).thenReturn(current);
        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current, latest});
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[0]);

        assertMigrationRefusal(
            migrationInfo,
            "Seeder refused: Flyway has not applied the complete current migration set"
        );
    }

    @Test
    void sanitizesFlywayInspectionFailureBeforeSeeding() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException("sensitive provider detail"));
        SeederService seederService = mock(SeederService.class);
        SeedDataRunner runner = runner(flyway, seederService);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> runner.run(mock(ApplicationArguments.class))
        );

        assertEquals(
            "Seeder refused: could not verify current Flyway migration state",
            exception.getMessage()
        );
        verifyNoInteractions(seederService);
    }

    private static void assertMigrationRefusal(
            MigrationInfoService migrationInfo,
            String expectedMessage) {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(migrationInfo);
        SeederService seederService = mock(SeederService.class);
        SeedDataRunner runner = runner(flyway, seederService);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> runner.run(mock(ApplicationArguments.class))
        );

        assertEquals(expectedMessage, exception.getMessage());
        verifyNoInteractions(seederService);
    }

    private static SeedDataRunner runner(Flyway flyway, SeederService seederService) {
        return new SeedDataRunner(
            new SeederProperties(),
            mock(SeederGuard.class),
            seederService,
            Clock.systemUTC(),
            flyway
        );
    }

    private static MigrationInfo migration(String version) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        return migration;
    }
}

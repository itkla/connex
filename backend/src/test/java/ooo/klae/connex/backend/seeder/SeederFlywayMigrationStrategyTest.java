package ooo.klae.connex.backend.seeder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SeederFlywayMigrationStrategyTest {

    @Test
    void verifiesTargetBeforeFlywayWritesAndThenReportsTiming() {
        SeederGuard guard = mock(SeederGuard.class);
        MigrationTimingReporter reporter = mock(MigrationTimingReporter.class);
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        SeederFlywayMigrationStrategy strategy =
            new SeederFlywayMigrationStrategy(guard, reporter);

        strategy.migrate(flyway);

        InOrder order = inOrder(guard, flyway, reporter);
        order.verify(guard).verify(dataSource);
        order.verify(flyway).migrate();
        order.verify(reporter).report(flyway);
    }
}

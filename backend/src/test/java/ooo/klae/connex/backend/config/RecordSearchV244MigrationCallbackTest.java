package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

class RecordSearchV244MigrationCallbackTest {
    @Test
    void backfillsBothProjectionsWithSourceAndWorkspaceGuards() throws Exception {
        Fixture fixture = fixture(new int[] {1});
        new RecordSearchV244MigrationCallback().handle(Event.AFTER_EACH_MIGRATE, fixture.context());
        verify(fixture.update()).setString(1, "やまだたろう");
        verify(fixture.update()).setString(2, "819096846208");
        verify(fixture.update()).setInt(3, 17);
        verify(fixture.update()).setInt(4, 7);
        verify(fixture.update()).setString(5, "ﾔﾏﾀﾞ タロウ");
        verify(fixture.update()).setString(6, "090-9684-6208");
        verify(fixture.connection(), times(2)).prepareStatement(contains(
            "AND BINARY name <=> BINARY ? AND BINARY phone <=> BINARY ?"));
        verify(fixture.connection()).prepareStatement(contains("FROM company"));
    }

    @Test
    void boundedPagesAdvancePastFiveHundredRows() throws Exception {
        Fixture fixture = fixture(new int[] {Statement.SUCCESS_NO_INFO});
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        AtomicInteger firstRow = new AtomicInteger();
        when(first.next()).thenAnswer(invocation -> firstRow.incrementAndGet() <= 500);
        when(first.getInt("id")).thenAnswer(invocation -> firstRow.get());
        when(second.next()).thenReturn(true, false);
        when(second.getInt("id")).thenReturn(501);
        when(fixture.select().executeQuery()).thenReturn(first, second);
        new RecordSearchV244MigrationCallback().handle(Event.AFTER_EACH_MIGRATE, fixture.context());
        verify(fixture.select()).setInt(1, 500);
        verify(fixture.select(), times(2)).setInt(2, 500);
    }

    @Test
    void failedBatchRefusesSuccessfulMigration() throws Exception {
        Fixture fixture = fixture(new int[] {Statement.EXECUTE_FAILED});
        assertThrows(FlywayException.class, () -> new RecordSearchV244MigrationCallback()
            .handle(Event.AFTER_EACH_MIGRATE, fixture.context()));
    }

    @Test
    void concurrentSourceChangeCanSkipAnUpdateWithoutOverwritingIt() throws Exception {
        Fixture fixture = fixture(new int[] {0});
        new RecordSearchV244MigrationCallback().handle(Event.AFTER_EACH_MIGRATE, fixture.context());
        verify(fixture.update()).executeBatch();
    }

    @Test
    void unrelatedVersionDoesNoDatabaseWork() throws Exception {
        Fixture fixture = fixture(new int[] {1});
        when(fixture.context().getMigrationInfo().getVersion()).thenReturn(MigrationVersion.fromVersion("243"));
        new RecordSearchV244MigrationCallback().handle(Event.AFTER_EACH_MIGRATE, fixture.context());
        verify(fixture.connection(), never()).prepareStatement(anyString());
    }

    private static Fixture fixture(int[] counts) throws Exception {
        Context context = mock(Context.class);
        MigrationInfo migration = mock(MigrationInfo.class);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement companySelect = mock(PreparedStatement.class);
        PreparedStatement companyUpdate = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        ResultSet empty = mock(ResultSet.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("244"));
        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("FROM person"))).thenReturn(select);
        when(connection.prepareStatement(contains("UPDATE person"))).thenReturn(update);
        when(connection.prepareStatement(contains("FROM company"))).thenReturn(companySelect);
        when(connection.prepareStatement(contains("UPDATE company"))).thenReturn(companyUpdate);
        when(companySelect.executeQuery()).thenReturn(empty);
        when(select.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getInt("id")).thenReturn(17);
        when(result.getInt("workspace_id")).thenReturn(7);
        when(result.getString("name")).thenReturn("ﾔﾏﾀﾞ タロウ");
        when(result.getString("phone")).thenReturn("090-9684-6208");
        when(update.executeBatch()).thenReturn(counts);
        when(companyUpdate.executeBatch()).thenReturn(new int[0]);
        return new Fixture(context, connection, select, update);
    }

    private record Fixture(Context context, Connection connection, PreparedStatement select, PreparedStatement update) {
    }
}

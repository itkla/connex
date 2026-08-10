package ooo.klae.connex.backend.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

class DealDuplicateNameV156MigrationCallbackTest {
    @Test
    void v156BackfillsNamesWithTheSharedJavaCanonicalizer() throws Exception {
        Fixture fixture = fixture("ＲＥＮＥＷＡＬ　ＯＰＰＯＲＴＵＮＩＴＹ", new int[] {1});

        new DealDuplicateNameV156MigrationCallback().handle(
            Event.AFTER_EACH_MIGRATE, fixture.context());

        verify(fixture.update()).setString(1, "renewal opportunity");
        verify(fixture.update()).setInt(2, 17);
        verify(fixture.update()).setInt(3, 7);
        verify(fixture.update()).setString(4, "ＲＥＮＥＷＡＬ　ＯＰＰＯＲＴＵＮＩＴＹ");
        verify(fixture.update()).addBatch();
    }

    @Test
    void v156LeavesUncanonicalizableLegacyNamesOnTheSafeNullFallback() throws Exception {
        Fixture fixture = fixture("Legacy\u200DDeal", new int[0]);

        new DealDuplicateNameV156MigrationCallback().handle(
            Event.AFTER_EACH_MIGRATE, fixture.context());

        verify(fixture.update(), never()).addBatch();
    }

    @Test
    void v156BinaryCompareLeavesAConcurrentRenameOnTheSafeNullFallback() throws Exception {
        Fixture fixture = fixture("Resume", new int[] {0});

        new DealDuplicateNameV156MigrationCallback().handle(
            Event.AFTER_EACH_MIGRATE, fixture.context());

        verify(fixture.connection()).prepareStatement(
            contains("AND BINARY name = BINARY ?"));
        verify(fixture.update()).addBatch();
    }

    @Test
    void anotherMigrationVersionIsLeftUntouched() throws Exception {
        Fixture fixture = fixture("Renewal", new int[] {1});
        when(fixture.context().getMigrationInfo().getVersion())
            .thenReturn(MigrationVersion.fromVersion("153"));

        new DealDuplicateNameV156MigrationCallback().handle(
            Event.AFTER_EACH_MIGRATE, fixture.context());

        verify(fixture.connection(), never()).prepareStatement(anyString());
        verify(fixture.update(), never()).addBatch();
    }

    private static Fixture fixture(String name, int[] updateCounts) throws Exception {
        Context context = mock(Context.class);
        MigrationInfo migration = mock(MigrationInfo.class);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("156"));
        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("SELECT id, workspace_id, name")))
            .thenReturn(select);
        when(connection.prepareStatement(contains("AND BINARY name = BINARY ?")))
            .thenReturn(update);
        when(select.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getInt("id")).thenReturn(17);
        when(result.getInt("workspace_id")).thenReturn(7);
        when(result.getString("name")).thenReturn(name);
        when(update.executeBatch()).thenReturn(updateCounts);
        return new Fixture(context, connection, update);
    }

    private record Fixture(
            Context context,
            Connection connection,
            PreparedStatement update) {
    }
}

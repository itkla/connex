package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Set;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

/** Verifies Flyway callback discovery and per-migration routing for the V126 audit guard. */
class AuditLogV126MigrationCallbackTest {
    private static final String SET_NONCE =
        "SET " + AuditLogV126MigrationCallback.NONCE_VARIABLE + " = ?";
    private static final Set<Event> SUPPORTED_EVENTS = Set.of(
        Event.BEFORE_EACH_MIGRATE,
        Event.AFTER_EACH_MIGRATE,
        Event.AFTER_EACH_MIGRATE_ERROR);

    private final AuditLogV126MigrationCallback callback =
        new AuditLogV126MigrationCallback();

    @Test
    void relevantEventsAreAdvertisedWithoutAnExecutionContext() {
        for (Event event : SUPPORTED_EVENTS) {
            assertTrue(callback.supports(event, null), event::name);
        }
    }

    @Test
    void irrelevantEventsAreRejectedWithoutAnExecutionContext() {
        for (Event event : Event.values()) {
            if (!SUPPORTED_EVENTS.contains(event)) {
                assertFalse(callback.supports(event, null), event::name);
            }
        }
    }

    @Test
    void nonV126MigrationsDoNothingForEverySupportedEvent() {
        Context context = context("125", null);

        for (Event event : SUPPORTED_EVENTS) {
            assertDoesNotThrow(() -> callback.handle(event, context));
        }

        verify(context, never()).getConnection();
    }

    @Test
    void v129EventsValidateTheFinalGuardSet() throws SQLException {
        for (Event event : SUPPORTED_EVENTS) {
            SQLException routeFailure = new SQLException(event.name());
            Connection connection = mock(Connection.class);
            PreparedStatement clear = mock(PreparedStatement.class);
            when(connection.prepareStatement(startsWith("SELECT TRIGGER_NAME")))
                .thenThrow(routeFailure);
            when(connection.prepareStatement(SET_NONCE)).thenReturn(clear);

            assertSame(
                routeFailure,
                assertThrows(
                    FlywayException.class,
                    () -> callback.handle(event, context("129", connection)))
                    .getCause());
            verifyNonceCleared(clear);
        }
    }

    @Test
    void v126EventsRouteToTheirMatchingGuardLifecycle() throws SQLException {
        SQLException beforeFailure = new SQLException("before V126 route");
        Connection beforeConnection = connectionWithId(41);
        PreparedStatement beforeNonce = mock(PreparedStatement.class);
        PreparedStatement beforeClear = mock(PreparedStatement.class);
        when(beforeConnection.prepareStatement(SET_NONCE))
            .thenReturn(beforeNonce, beforeClear);
        when(beforeConnection.prepareStatement(startsWith("SELECT TRIGGER_NAME")))
            .thenThrow(beforeFailure);

        SQLException afterFailure = new SQLException("after V126 route");
        Connection afterConnection = mock(Connection.class);
        Statement connectionIdStatement = mock(Statement.class);
        Statement nonceStatement = mock(Statement.class);
        PreparedStatement afterClear = mock(PreparedStatement.class);
        when(afterConnection.createStatement())
            .thenReturn(connectionIdStatement, nonceStatement);
        stubConnectionId(connectionIdStatement, 42);
        when(nonceStatement.executeQuery(
            "SELECT LOWER(HEX(" + AuditLogV126MigrationCallback.NONCE_VARIABLE + "))"))
            .thenThrow(afterFailure);
        when(afterConnection.prepareStatement(SET_NONCE)).thenReturn(afterClear);

        SQLException errorFailure = new SQLException("failed V126 route");
        Connection errorConnection = mock(Connection.class);
        PreparedStatement errorClear = mock(PreparedStatement.class);
        PreparedStatement errorClearAfterFailure = mock(PreparedStatement.class);
        when(errorConnection.prepareStatement(SET_NONCE))
            .thenReturn(errorClear, errorClearAfterFailure);
        when(errorConnection.prepareStatement(startsWith("SELECT TRIGGER_NAME")))
            .thenThrow(errorFailure);

        assertSame(
            beforeFailure,
            assertThrows(
                FlywayException.class,
                () -> callback.handle(
                    Event.BEFORE_EACH_MIGRATE,
                    context("126", beforeConnection)))
                .getCause());
        assertSame(
            afterFailure,
            assertThrows(
                FlywayException.class,
                () -> callback.handle(
                    Event.AFTER_EACH_MIGRATE,
                    context("126", afterConnection)))
                .getCause());
        assertSame(
            errorFailure,
            assertThrows(
                FlywayException.class,
                () -> callback.handle(
                    Event.AFTER_EACH_MIGRATE_ERROR,
                    context("126", errorConnection)))
                .getCause());
        verifyNonceCleared(beforeClear);
        verifyNonceCleared(afterClear);
        verifyNonceCleared(errorClear);
        verifyNonceCleared(errorClearAfterFailure);
    }

    @Test
    void v126CallbackFailureClearsTheTrustedNonceBeforeItEscapes() throws SQLException {
        Connection connection = connectionWithId(43);
        PreparedStatement nonce = mock(PreparedStatement.class);
        PreparedStatement clear = mock(PreparedStatement.class);
        PreparedStatement triggerQuery = mock(PreparedStatement.class);
        ResultSet triggerRows = mock(ResultSet.class);
        when(connection.prepareStatement(SET_NONCE)).thenReturn(nonce, clear);
        when(connection.prepareStatement(startsWith("SELECT TRIGGER_NAME")))
            .thenReturn(triggerQuery);
        when(triggerQuery.executeQuery()).thenReturn(triggerRows);
        when(triggerRows.next()).thenReturn(true, true);

        assertThrows(
            FlywayException.class,
            () -> callback.handle(
                Event.BEFORE_EACH_MIGRATE,
                context("126", connection)));

        verifyNonceCleared(clear);
    }

    private static Context context(String version, Connection connection) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        Context context = mock(Context.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }

    private static Connection connectionWithId(long connectionId) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        stubConnectionId(statement, connectionId);
        return connection;
    }

    private static void stubConnectionId(Statement statement, long connectionId)
            throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(statement.executeQuery("SELECT CONNECTION_ID()")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getLong(1)).thenReturn(connectionId);
    }

    private static void verifyNonceCleared(PreparedStatement statement) throws SQLException {
        verify(statement).setNull(1, Types.VARBINARY);
        verify(statement).execute();
    }
}

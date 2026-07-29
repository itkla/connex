package ooo.klae.connex.backend.config;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code audit_log} append-only across the V126 and V127 trigger migrations.
 */
@Component
public class AuditLogV126MigrationCallback implements Callback {
    static final String LEGACY_TRIGGER = "trg_audit_log_no_update";
    static final String PERMANENT_TRIGGER = "trg_audit_log_no_update_v127";
    static final String DELETE_TRIGGER = "trg_audit_log_no_delete";
    static final String TEMPORARY_TRIGGER = "trg_audit_log_v126_migration_guard";
    static final String UPDATE_REPAIR_TRIGGER = "trg_audit_log_no_update_repair";
    static final String DELETE_REPAIR_TRIGGER = "trg_audit_log_no_delete_repair";
    static final String NONCE_VARIABLE = "@connex_audit_v126_nonce";
    static final String PERMANENT_BODY =
        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only'";

    private static final String UPDATE_EVENT = "UPDATE";
    private static final String DELETE_EVENT = "DELETE";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RECOVERABLE_TEMPORARY_BODY = Pattern.compile(
        "^BEGIN IF CONNECTION_ID\\(\\) <> [1-9][0-9]*"
            + " OR @connex_audit_v126_nonce IS NULL"
            + " OR BINARY @connex_audit_v126_nonce <> X'[0-9a-f]{64}'"
            + " THEN SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT = 'audit_log is append-only'; END IF; END$");

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE_ERROR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        if (!supports(event, context)) {
            return;
        }
        if (context == null) {
            throw new FlywayException("Flyway did not provide an execution context for the V126 audit guard");
        }
        if (!isAuditGuardMigration(context)) {
            return;
        }
        Connection connection = context.getConnection();
        if (connection == null) {
            throw new FlywayException("Flyway did not provide an audit guard migration connection");
        }
        try {
            if (isV126(context)) {
                if (event == Event.BEFORE_EACH_MIGRATE) {
                    beforeV126(connection);
                } else if (event == Event.AFTER_EACH_MIGRATE) {
                    afterV126(connection);
                } else {
                    afterV126Failure(connection);
                }
            } else {
                ensureFinalGuards(connection);
            }
        } catch (SQLException exception) {
            clearNonceAfterFailure(connection, exception);
            throw new FlywayException(
                "Could not preserve the audit append-only guard during migration",
                exception);
        } catch (RuntimeException exception) {
            clearNonceAfterFailure(connection, exception);
            throw exception;
        }
    }

    @Override
    public String getCallbackName() {
        return "audit-log-migration-guard";
    }

    private void beforeV126(Connection connection) throws SQLException {
        long connectionId = connectionId(connection);
        String nonce = nonce();
        setNonce(connection, HexFormat.of().parseHex(nonce));

        ensureExactGuard(
            connection,
            DELETE_TRIGGER,
            DELETE_EVENT,
            DELETE_REPAIR_TRIGGER);
        ensureExactGuard(
            connection,
            LEGACY_TRIGGER,
            UPDATE_EVENT,
            UPDATE_REPAIR_TRIGGER);

        TriggerDefinition temporary = findTrigger(connection, TEMPORARY_TRIGGER);
        if (temporary != null) {
            requireRecoverableTemporary(temporary);
            dropTrigger(connection, TEMPORARY_TRIGGER);
        }

        ensureTemporaryGuard(connection, connectionId, nonce);
    }

    private void afterV126(Connection connection) throws SQLException {
        long connectionId = connectionId(connection);
        String nonce = currentNonce(connection);
        if (nonce == null) {
            throw new FlywayException("V126 audit migration callback state was not retained");
        }

        requireExact(
            findTrigger(connection, TEMPORARY_TRIGGER),
            TEMPORARY_TRIGGER,
            UPDATE_EVENT,
            temporaryBody(connectionId, nonce));
        ensureFinalGuards(connection);

        dropTrigger(connection, TEMPORARY_TRIGGER);
        setNonce(connection, null);
    }

    private void afterV126Failure(Connection connection) throws SQLException {
        setNonce(connection, null);
        ensureExactGuard(
            connection,
            DELETE_TRIGGER,
            DELETE_EVENT,
            DELETE_REPAIR_TRIGGER);
        TriggerDefinition temporary = findTrigger(connection, TEMPORARY_TRIGGER);
        if (temporary == null) {
            ensureExactGuard(
                connection,
                LEGACY_TRIGGER,
                UPDATE_EVENT,
                UPDATE_REPAIR_TRIGGER);
        } else {
            requireRecoverableTemporary(temporary);
        }
    }

    private static boolean isAuditGuardMigration(Context context) {
        return isV126(context) || isVersion(context, "127");
    }

    private static boolean isV126(Context context) {
        return isVersion(context, "126");
    }

    private static boolean isVersion(Context context, String version) {
        MigrationInfo migration = context.getMigrationInfo();
        return migration != null
            && migration.getVersion() != null
            && version.equals(migration.getVersion().getVersion());
    }

    private static void ensureFinalGuards(Connection connection) throws SQLException {
        createIfMissing(connection, LEGACY_TRIGGER, UPDATE_EVENT);
        createIfMissing(connection, PERMANENT_TRIGGER, UPDATE_EVENT);
        createIfMissing(connection, DELETE_TRIGGER, DELETE_EVENT);

        boolean legacyExact = isExact(
            findTrigger(connection, LEGACY_TRIGGER),
            LEGACY_TRIGGER,
            UPDATE_EVENT,
            PERMANENT_BODY);
        boolean permanentExact = isExact(
            findTrigger(connection, PERMANENT_TRIGGER),
            PERMANENT_TRIGGER,
            UPDATE_EVENT,
            PERMANENT_BODY);
        boolean deleteExact = isExact(
            findTrigger(connection, DELETE_TRIGGER),
            DELETE_TRIGGER,
            DELETE_EVENT,
            PERMANENT_BODY);

        if (!deleteExact) {
            ensureStrictRepair(connection, DELETE_REPAIR_TRIGGER, DELETE_EVENT);
        }
        if (!legacyExact || !permanentExact) {
            ensureStrictRepair(connection, UPDATE_REPAIR_TRIGGER, UPDATE_EVENT);
        }
        if (!legacyExact || !permanentExact || !deleteExact) {
            throw new FlywayException("One or more permanent audit triggers have unexpected definitions");
        }

        dropExactRepair(connection, UPDATE_REPAIR_TRIGGER, UPDATE_EVENT);
        dropExactRepair(connection, DELETE_REPAIR_TRIGGER, DELETE_EVENT);
    }

    private static void ensureExactGuard(
            Connection connection,
            String name,
            String event,
            String repairName) throws SQLException {
        createIfMissing(connection, name, event);
        TriggerDefinition definition = findTrigger(connection, name);
        if (!isExact(definition, name, event, PERMANENT_BODY)) {
            ensureStrictRepair(connection, repairName, event);
            throw new FlywayException("Audit trigger " + name + " has an unexpected definition");
        }
        dropExactRepair(connection, repairName, event);
    }

    private static void createIfMissing(
            Connection connection,
            String name,
            String event) throws SQLException {
        if (findTrigger(connection, name) == null) {
            createPermanentTrigger(connection, name, event);
        }
    }

    private static void ensureStrictRepair(
            Connection connection,
            String name,
            String event) throws SQLException {
        createIfMissing(connection, name, event);
        requirePermanent(findTrigger(connection, name), name, event);
    }

    private static void dropExactRepair(
            Connection connection,
            String name,
            String event) throws SQLException {
        TriggerDefinition repair = findTrigger(connection, name);
        if (repair != null) {
            requirePermanent(repair, name, event);
            dropTrigger(connection, name);
        }
    }

    private static void createPermanentTrigger(
            Connection connection,
            String name,
            String event) throws SQLException {
        execute(connection, "CREATE TRIGGER " + name
            + " BEFORE " + event + " ON audit_log FOR EACH ROW " + PERMANENT_BODY);
    }

    private static void requirePermanent(
            TriggerDefinition definition,
            String name,
            String event) {
        requireExact(definition, name, event, PERMANENT_BODY);
    }

    private static void requireRecoverableTemporary(TriggerDefinition definition) {
        requireMetadata(definition, TEMPORARY_TRIGGER, UPDATE_EVENT);
        if (!RECOVERABLE_TEMPORARY_BODY.matcher(definition.actionStatement()).matches()) {
            throw new FlywayException("Existing V126 audit migration guard has an unexpected definition");
        }
    }

    private static void requireExact(
            TriggerDefinition definition,
            String name,
            String event,
            String expectedBody) {
        if (!isExact(definition, name, event, expectedBody)) {
            throw new FlywayException("Audit trigger " + name + " has an unexpected body");
        }
    }

    private static boolean isExact(
            TriggerDefinition definition,
            String name,
            String event,
            String expectedBody) {
        return definition != null
                && name.equals(definition.name())
                && definition.currentSchema()
                && "audit_log".equals(definition.table())
                && "BEFORE".equals(definition.timing())
                && event.equals(definition.event())
                && "ROW".equals(definition.orientation())
                && definition.condition() == null
                && expectedBody.equals(definition.actionStatement());
    }

    private static void requireMetadata(
            TriggerDefinition definition,
            String name,
            String event) {
        if (definition == null
                || !name.equals(definition.name())
                || !definition.currentSchema()
                || !"audit_log".equals(definition.table())
                || !"BEFORE".equals(definition.timing())
                || !event.equals(definition.event())
                || !"ROW".equals(definition.orientation())
                || definition.condition() != null) {
            throw new FlywayException("Audit trigger " + name + " has an unexpected definition");
        }
    }

    private static void ensureTemporaryGuard(
            Connection connection,
            long connectionId,
            String nonce) throws SQLException {
        String body = temporaryBody(connectionId, nonce);
        TriggerDefinition existing = findTrigger(connection, TEMPORARY_TRIGGER);
        if (existing == null) {
            execute(connection, "CREATE TRIGGER " + TEMPORARY_TRIGGER
                + " BEFORE UPDATE ON audit_log FOR EACH ROW " + body);
        }
        requireExact(
            findTrigger(connection, TEMPORARY_TRIGGER),
            TEMPORARY_TRIGGER,
            UPDATE_EVENT,
            body);
    }

    private static TriggerDefinition findTrigger(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT TRIGGER_NAME,
                       EVENT_OBJECT_SCHEMA = DATABASE() AS current_schema,
                       EVENT_OBJECT_TABLE,
                       ACTION_TIMING,
                       EVENT_MANIPULATION,
                       ACTION_ORIENTATION,
                       ACTION_CONDITION,
                       ACTION_STATEMENT
                FROM information_schema.TRIGGERS
                WHERE TRIGGER_SCHEMA = DATABASE()
                  AND TRIGGER_NAME = ?
                """)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                TriggerDefinition definition = new TriggerDefinition(
                    result.getString("TRIGGER_NAME"),
                    result.getBoolean("current_schema"),
                    result.getString("EVENT_OBJECT_TABLE"),
                    result.getString("ACTION_TIMING"),
                    result.getString("EVENT_MANIPULATION"),
                    result.getString("ACTION_ORIENTATION"),
                    result.getString("ACTION_CONDITION"),
                    result.getString("ACTION_STATEMENT"));
                if (result.next()) {
                    throw new FlywayException("Audit trigger name is not unique in the current schema");
                }
                return definition;
            }
        }
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (!result.next()) {
                throw new FlywayException("MySQL did not return the Flyway connection id");
            }
            return result.getLong(1);
        }
    }

    private static String nonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String currentNonce(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                    "SELECT LOWER(HEX(" + NONCE_VARIABLE + "))")) {
            if (!result.next()) {
                throw new FlywayException("MySQL did not return the V126 audit migration nonce");
            }
            String nonce = result.getString(1);
            return nonce != null && nonce.matches("[0-9a-f]{64}") ? nonce : null;
        }
    }

    private static String temporaryBody(long connectionId, String nonce) {
        return "BEGIN IF CONNECTION_ID() <> " + connectionId
            + " OR " + NONCE_VARIABLE + " IS NULL"
            + " OR BINARY " + NONCE_VARIABLE + " <> X'" + nonce + "'"
            + " THEN " + PERMANENT_BODY + "; END IF; END";
    }

    private static void setNonce(Connection connection, byte[] nonce) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SET " + NONCE_VARIABLE + " = ?")) {
            if (nonce == null) {
                statement.setNull(1, Types.VARBINARY);
            } else {
                statement.setBytes(1, nonce);
            }
            statement.execute();
        }
    }

    private static void clearNonceAfterFailure(Connection connection, Throwable failure) {
        try {
            setNonce(connection, null);
        } catch (SQLException cleanupException) {
            if (cleanupException != failure) {
                failure.addSuppressed(cleanupException);
            }
        }
    }

    private static void dropTrigger(Connection connection, String name) throws SQLException {
        execute(connection, "DROP TRIGGER " + name);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record TriggerDefinition(
            String name,
            boolean currentSchema,
            String table,
            String timing,
            String event,
            String orientation,
            String condition,
            String actionStatement) {
    }
}

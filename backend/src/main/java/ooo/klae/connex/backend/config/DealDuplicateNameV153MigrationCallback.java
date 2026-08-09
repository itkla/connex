package ooo.klae.connex.backend.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.util.CanonicalNameNormalizer;

/** Backfills the indexed canonical deal-name key introduced by tenant migration V153. */
@Component
public class DealDuplicateNameV153MigrationCallback implements Callback {
    private static final int PAGE_SIZE = 500;

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        if (!supports(event, context) || !isV153(context)) {
            return;
        }
        if (context == null || context.getConnection() == null) {
            throw new FlywayException(
                "Flyway did not provide a deal duplicate-name backfill connection");
        }
        try {
            backfill(context.getConnection());
        } catch (SQLException exception) {
            throw new FlywayException("Could not backfill canonical deal duplicate names", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "deal-duplicate-name-v153-backfill";
    }

    private static boolean isV153(Context context) {
        if (context == null) {
            return false;
        }
        MigrationInfo migration = context.getMigrationInfo();
        return migration != null
            && migration.getVersion() != null
            && "153".equals(migration.getVersion().getVersion());
    }

    private static void backfill(Connection connection) throws SQLException {
        int afterId = 0;
        while (true) {
            int lastId = afterId;
            int rows = 0;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, workspace_id, name FROM deal "
                        + "WHERE id > ? AND duplicate_normalized_name IS NULL "
                        + "ORDER BY id LIMIT ?");
                    PreparedStatement update = connection.prepareStatement(
                        "UPDATE deal SET duplicate_normalized_name = ? "
                            + "WHERE id = ? AND workspace_id = ? "
                            + "AND BINARY name = BINARY ? "
                            + "AND duplicate_normalized_name IS NULL")) {
                select.setInt(1, afterId);
                select.setInt(2, PAGE_SIZE);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        int id = result.getInt("id");
                        int workspaceId = result.getInt("workspace_id");
                        String name = result.getString("name");
                        Optional<String> normalized = CanonicalNameNormalizer.normalize(name);
                        if (normalized.isPresent()) {
                            update.setString(1, normalized.get());
                            update.setInt(2, id);
                            update.setInt(3, workspaceId);
                            update.setString(4, name);
                            update.addBatch();
                        }
                        lastId = id;
                        rows++;
                    }
                }
                requireSuccessfulBatch(update.executeBatch());
            }
            if (rows == 0) {
                return;
            }
            if (lastId <= afterId) {
                throw new FlywayException("Canonical deal-name backfill cursor did not advance");
            }
            afterId = lastId;
            if (rows < PAGE_SIZE) {
                return;
            }
        }
    }

    private static void requireSuccessfulBatch(int[] results) {
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                throw new FlywayException("Canonical deal-name backfill update failed");
            }
        }
    }
}

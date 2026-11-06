package ooo.klae.connex.backend.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.util.SearchNormalizer;

/** Backfills the indexed search-only name and phone projections introduced by tenant migration V244. */
@Component
public class RecordSearchV244MigrationCallback implements Callback {
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
        if (!supports(event, context) || !isV244(context)) {
            return;
        }
        if (context == null || context.getConnection() == null) {
            throw new FlywayException(
                "Flyway did not provide a record search-key backfill connection");
        }
        try {
            backfill(context.getConnection(), "person");
            backfill(context.getConnection(), "company");
        } catch (SQLException exception) {
            throw new FlywayException("Could not backfill record search keys", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return "record-search-v244-backfill";
    }

    private static boolean isV244(Context context) {
        if (context == null) {
            return false;
        }
        MigrationInfo migration = context.getMigrationInfo();
        return migration != null
            && migration.getVersion() != null
            && "244".equals(migration.getVersion().getVersion());
    }

    private static void backfill(Connection connection, String table) throws SQLException {
        int afterId = 0;
        while (true) {
            int lastId = afterId;
            int rows = 0;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, workspace_id, name, phone FROM " + table
                        + " WHERE id > ? AND (search_name IS NULL OR search_phone IS NULL) ORDER BY id LIMIT ?");
                    PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET search_name = ?, search_phone = ?, "
                            + "search_key_revision = search_key_revision + 1, updated_at = updated_at "
                            + "WHERE id = ? AND workspace_id = ? "
                            + "AND BINARY name <=> BINARY ? AND BINARY phone <=> BINARY ? "
                            + "AND (search_name IS NULL OR search_phone IS NULL)")) {
                select.setInt(1, afterId);
                select.setInt(2, PAGE_SIZE);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        int id = result.getInt("id");
                        String name = result.getString("name");
                        String phone = result.getString("phone");
                        update.setString(1, SearchNormalizer.name(name));
                        update.setString(2, SearchNormalizer.phone(phone));
                        update.setInt(3, id);
                        update.setInt(4, result.getInt("workspace_id"));
                        update.setString(5, name);
                        update.setString(6, phone);
                        update.addBatch();
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
                throw new FlywayException("Record search-key backfill cursor did not advance");
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
                throw new FlywayException("Record search-key backfill update failed");
            }
        }
    }
}

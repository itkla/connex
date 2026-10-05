package ooo.klae.connex.backend.architecture;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Live current-database metadata captured inside the calling test's schema lifecycle. */
record SchemaMetadataSnapshot(
        Set<Column> columns, Set<Column> leadingIndexes) {
    SchemaMetadataSnapshot {
        columns = Set.copyOf(columns);
        leadingIndexes = Set.copyOf(leadingIndexes);
    }

    static SchemaMetadataSnapshot read(Connection connection) throws SQLException {
        return new SchemaMetadataSnapshot(readColumns(connection), readLeadingIndexes(connection));
    }

    static Set<Column> readLeadingIndexes(Connection connection) throws SQLException {
        return readPairs(connection,
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND SEQ_IN_INDEX = 1");
    }

    private static Set<Column> readColumns(Connection connection) throws SQLException {
        return readPairs(connection,
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE()");
    }

    private static Set<Column> readPairs(Connection connection, String sql) throws SQLException {
        Set<Column> columns = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                columns.add(new Column(result.getString(1), result.getString(2)));
            }
        }
        return Set.copyOf(columns);
    }

    boolean columnExists(String table, String column) {
        return columns.contains(new Column(table, column));
    }

    boolean leadingIndexExists(String table, String column) {
        return leadingIndexes.contains(new Column(table, column));
    }

    record Column(String table, String column) {
    }
}

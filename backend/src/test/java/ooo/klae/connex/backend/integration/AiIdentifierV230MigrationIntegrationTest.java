package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Replays only V230 on populated V229-shaped clones; never migrates a fresh tenant lineage. */
class AiIdentifierV230MigrationIntegrationTest {
    @Test
    void populatedCoreTablesUpgradeInstantlyAndRetainLegacyWritersAndV156Trigger() throws Exception {
        String url = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "Database credentials required");
        try (Connection source = DriverManager.getConnection(url, username, password);
                MySqlScratchCatalog scratch = MySqlScratchCatalog.create(url, username, password,
                        "identifier_v230_", "utf8mb4", "utf8mb4_0900_ai_ci");
                Connection connection = DriverManager.getConnection(scratch.url(), username, password);
                var statement = connection.createStatement()) {
            String catalog = source.getCatalog();
            assertTrue(catalog.matches("[A-Za-z0-9_]+"));
            for (String table : List.of("person", "company", "deal")) {
                statement.execute("CREATE TABLE " + table + " LIKE `" + catalog + "`." + table);
                try (var columns = connection.getMetaData().getColumns(scratch.name(), null, table, "ai_identifier_literal_key")) {
                    if (columns.next()) {
                        statement.execute("ALTER TABLE " + table
                                + " DROP COLUMN ai_identifier_literal_key, DROP COLUMN ai_identifier_label_key,"
                                + " DROP COLUMN ai_identifier_source_name, DROP COLUMN ai_identifier_key_version, ALGORITHM=INSTANT");
                    }
                }
                String extraColumns = table.equals("deal") ? ", pipeline_id, stage_id" : "";
                String extraValues = table.equals("deal") ? ", 1, 1" : "";
                statement.executeUpdate("INSERT INTO " + table + " (id, workspace_id, name" + extraColumns
                        + ") VALUES (1, 1, '㍿ Legacy'" + extraValues + ")");
            }
            String v156;
            try (var input = new ClassPathResource("db/migration/tenant/V156__deal_duplicate_review_proofs.sql").getInputStream()) {
                v156 = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            statement.execute(v156.substring(v156.indexOf("CREATE TRIGGER"), v156.indexOf("END//") + 3));
            var migration = new ClassPathResource("db/migration/tenant/V230__ai_identifier_lookup_keys.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            for (String table : List.of("person", "company", "deal")) {
                try (var rows = statement.executeQuery("SELECT name, ai_identifier_literal_key, ai_identifier_label_key,"
                        + " ai_identifier_source_name, ai_identifier_key_version FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals("㍿ Legacy", rows.getString(1));
                    for (int column = 2; column <= 5; column++) {
                        assertNull(rows.getObject(column));
                    }
                }
                statement.executeUpdate("UPDATE " + table + " SET name = '㍿ LEGACY ' WHERE id = 1");
                statement.executeUpdate("INSERT INTO " + table + " (workspace_id, name"
                        + (table.equals("deal") ? ", pipeline_id, stage_id" : "")
                        + ") VALUES (1, 'Old binary insert'" + (table.equals("deal") ? ", 1, 1" : "") + ")");
                try (var columns = connection.getMetaData().getColumns(scratch.name(), null, table, "ai_identifier_%")) {
                    int count = 0;
                    while (columns.next()) {
                        assertEquals("YES", columns.getString("IS_NULLABLE"));
                        count++;
                    }
                    assertEquals(4, count);
                }
            }
            statement.executeUpdate("UPDATE deal SET duplicate_normalized_name = 'old', duplicate_name_revision = 1 WHERE id = 1");
            statement.executeUpdate("UPDATE deal SET name = 'Old binary rename' WHERE id = 1");
            try (var rows = statement.executeQuery("SELECT duplicate_normalized_name FROM deal WHERE id = 1")) {
                assertTrue(rows.next());
                assertNull(rows.getString(1), "V156 legacy trigger must still invalidate its own duplicate key");
            }
        }
    }
}

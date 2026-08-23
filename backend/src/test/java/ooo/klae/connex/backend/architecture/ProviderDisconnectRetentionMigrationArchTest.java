package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ProviderDisconnectRetentionMigrationArchTest {

    @Test
    void migrationAddsInternalStatesAndRemovesTheDeadSyncTimestamp() throws Exception {
        String migration = resource(
            "db/migration/control/V187__provider_disconnect_retention.sql");

        assertTrue(migration.contains("'revoking'"));
        assertTrue(migration.contains("'disconnected'"));
        assertTrue(migration.contains("DROP COLUMN last_sync_at"));
        assertTrue(migration.contains("credential_ref IS NULL"));
        assertTrue(migration.contains("access_token_expires_at IS NULL"));
        assertTrue(migration.contains("expected_connection_id"));
        assertTrue(migration.contains("expected_credential_generation"));
        assertTrue(migration.contains("upgrade_invalidated"));
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = ProviderDisconnectRetentionMigrationArchTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing migration " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

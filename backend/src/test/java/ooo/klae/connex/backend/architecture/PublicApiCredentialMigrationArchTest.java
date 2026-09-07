package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PublicApiCredentialMigrationArchTest {

    @Test
    void revokerForeignKeyIsSetNullAndNoCheckReferencesIt() throws Exception {
        String migration = resource("db/migration/control/V202__api_credentials.sql");
        String normalized = migration.replaceAll("\\s+", " ");

        assertTrue(Pattern.compile(
            "CONSTRAINT fk_api_credential_revoker .*?ON DELETE SET NULL",
            Pattern.CASE_INSENSITIVE).matcher(normalized).find());
        assertFalse(Pattern.compile(
            "CHECK \\([^;]*revoked_by_id",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(migration).find());
    }

    @Test
    void membershipForeignKeyStaysGenerationWideAndCascades() throws Exception {
        String normalized = resource("db/migration/control/V202__api_credentials.sql")
            .replaceAll("\\s+", " ");

        assertTrue(Pattern.compile(
            "CONSTRAINT fk_api_credential_membership FOREIGN KEY "
                + "\\(workspace_id, created_by_id, membership_id\\) "
                + "REFERENCES workspace_member\\(workspace_id, user_id, membership_id\\) "
                + "ON DELETE CASCADE",
            Pattern.CASE_INSENSITIVE).matcher(normalized).find(),
            "Account erasure is the only transaction that appends into more than one audit chain. "
                + "That holds because a missing workspace_member row has already cascaded every "
                + "credential away, so membership cleanup emits no audit on any fresh-membership "
                + "path. Narrowing this foreign key to (workspace_id, created_by_id) or weakening "
                + "the cascade makes fresh membership a second multi-head transaction and "
                + "reintroduces the crossed-head deadlock.");
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = PublicApiCredentialMigrationArchTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing migration " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

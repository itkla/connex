package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Executes the global cutover only in a disposable catalog cloned from the migrated schema.
 * CREATE TABLE LIKE retains indexes and checks, renames CHECK constraints and omits foreign keys;
 * these fixtures prove behavior, while PrivilegedCredentialProvenanceIntegrationTest proves the
 * named source constraint in the real schema. A forced final SQL failure exercises Flyway's
 * transaction and repair/retry boundary without touching any other class's fixtures.
 */
@SpringBootTest
class PrivilegedMfaCutoverMigrationIntegrationTest {
    private static final List<String> TABLES = List.of(
        "organization", "org_member", "workspace", "workspace_member", "workspace_role_permission",
        "app_user", "webauthn_user_entity", "webauthn_credential",
        "privileged_credential_attestation", "privileged_mfa_attestation_grant");

    @Autowired private DataSource dataSource;

    private MySqlScratchCatalog scratchCatalog;
    private String username;
    private String password;

    @BeforeEach
    void createScratchFixture() throws SQLException {
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        scratchCatalog = MySqlScratchCatalog.create(System.getenv("CONNEX_DB_URL"), username, password,
            "connex_mfa_cutover_it_", "utf8mb4", "utf8mb4_0900_ai_ci");
        try (Connection source = dataSource.getConnection();
                Connection scratch = connection(); Statement statement = scratch.createStatement()) {
            String catalog = source.getCatalog();
            assertNotNull(catalog);
            assertTrue(catalog.matches("[A-Za-z0-9_]{1,64}"));
            for (String table : TABLES) {
                statement.execute("CREATE TABLE `" + table + "` LIKE `" + catalog + "`.`" + table + "`");
            }
            seed(statement);
        }
    }

    @AfterEach
    void dropScratchFixture() throws SQLException {
        if (scratchCatalog != null) {
            scratchCatalog.close();
        }
    }

    @Test
    void populationMatchesTheLiveMapperAcrossAllPrivilegeBranches() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        String resource = "mappers/UserMapper.xml";
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Set<String> mapperPairs = new TreeSet<>();
        try (Connection connection = connection();
                SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<Integer> users = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
            for (int orgId : List.of(1, 2, 3, 4)) {
                for (int userId : mapper.privilegedInOrganizationAmong(orgId, users)) {
                    mapperPairs.add(userId + ":" + orgId);
                }
            }
            assertEquals(Set.of("1:1", "2:1", "3:1", "3:2", "4:1", "5:1", "5:2", "11:1"), mapperPairs);
            for (String block : PrivilegedMfaCutoverMigrationTest.populationBlocks()) {
                assertEquals(mapperPairs, populationPairs(connection, block));
            }
        }
    }

    @Test
    void cutoverResetsCoveragePreservesPopulationProvenanceAndIsIdempotent() throws Exception {
        List<List<String>> credentialBefore;
        List<List<String>> closedGrantsBefore;
        try (Connection connection = connection()) {
            credentialBefore = rows(connection, "SELECT * FROM webauthn_credential ORDER BY id");
            closedGrantsBefore = rows(connection,
                "SELECT * FROM privileged_mfa_attestation_grant WHERE id IN (2, 3) ORDER BY id");
        }

        Flyway flyway = migrate("classpath:db/migration/control");
        assertEquals("225", flyway.info().current().getVersion().getVersion());

        try (Connection connection = connection()) {
            assertCutover(connection);
            assertEquals(credentialBefore, rows(connection, "SELECT * FROM webauthn_credential ORDER BY id"));
            assertEquals(closedGrantsBefore, rows(connection,
                "SELECT * FROM privileged_mfa_attestation_grant WHERE id IN (2, 3) ORDER BY id"));
            List<List<List<String>>> after = snapshot(connection);
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(PrivilegedMfaCutoverMigrationTest.RESOURCE));
            connection.commit();
            assertEquals(after, snapshot(connection), "a second execution must not change timestamps or provenance");
        }
    }

    @Test
    void flywayRollsBackEveryWriteOnFinalFailureAndACleanRetrySucceeds(@TempDir Path migrations) throws Exception {
        List<List<List<String>>> before;
        try (Connection connection = connection()) {
            before = snapshot(connection);
        }
        Files.writeString(migrations.resolve("V225__privileged_mfa_cutover.sql"),
            PrivilegedMfaCutoverMigrationTest.migrationSql()
                + "\nINSERT INTO missing_cutover_failure_probe (id) VALUES (1);\n");
        Flyway failing = flyway("filesystem:" + migrations);

        assertThrows(FlywayException.class, failing::migrate);

        try (Connection connection = connection()) {
            assertEquals(before, snapshot(connection));
            assertEquals(List.of(List.of("0")), rows(connection,
                "SELECT success FROM flyway_schema_history WHERE version = '225'"));
        }
        Flyway clean = flyway("classpath:db/migration/control");
        clean.repair();
        clean.migrate();
        try (Connection connection = connection()) {
            assertCutover(connection);
            assertEquals(List.of(List.of("1")), rows(connection,
                "SELECT success FROM flyway_schema_history WHERE version = '225'"));
        }
    }

    /**
     * The runbook's reconciliation for a broken rollback floor only narrows trust. It removes every
     * grantor, inherited and founder row attested since the violation began, including one that
     * propagated after the older binary stopped, clears every founder flag and revokes open codes.
     * The cutover's own coverage survives and nothing new is trusted.
     */
    @Test
    void theRunbookReconciliationRemovesEverythingSinceTheViolationAndTrustsNothingNew() throws Exception {
        migrate("classpath:db/migration/control");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String coverage = "SELECT credential_row_id, org_id, source, COALESCE(grant_id, ''), attested_at"
                + " FROM privileged_credential_attestation ORDER BY credential_row_id, org_id";
            List<List<String>> trustedAtCutover = rows(connection, coverage);
            statement.execute("SET @violation_start ="
                + " (SELECT MAX(attested_at) + INTERVAL 1 MINUTE FROM privileged_credential_attestation)");
            statement.executeUpdate(
                "UPDATE workspace_member SET role = 'admin' WHERE workspace_id = 1 AND user_id = 10");
            statement.executeUpdate("""
                INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source, attested_at)
                VALUES (100, 1, 'GRANTOR', @violation_start + INTERVAL 10 MINUTE),
                       (101, 1, 'INHERITED', @violation_start + INTERVAL 2 HOUR)
                """);
            statement.executeUpdate(
                "UPDATE org_member SET org_role = 'owner', founder = TRUE WHERE org_id = 2 AND user_id = 3");
            statement.executeUpdate("""
                INSERT INTO webauthn_credential (id, credential_id, user_entity_user_id, public_key)
                VALUES (32, X'20', 'h3', X'01')
                """);
            statement.executeUpdate("""
                INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source, attested_at)
                VALUES (32, 2, 'FOUNDER', @violation_start + INTERVAL 3 HOUR)
                """);
            statement.executeUpdate("""
                INSERT INTO privileged_mfa_attestation_grant
                    (id, org_id, grantor_user_id, grantee_user_id, code_digest, expires_at, created_at)
                VALUES (5, 1, 1, 10, REPEAT('e', 64), '2099-01-01', @violation_start + INTERVAL 5 MINUTE)
                """);

            for (String sql : reconciliationStatements()) {
                statement.execute(sql);
            }

            assertEquals(trustedAtCutover, rows(connection, coverage));
            assertEquals(List.of(List.of("0")), rows(connection, "SELECT COUNT(*) FROM org_member WHERE founder"));
            assertEquals(List.of(List.of("1", "")), rows(connection, "SELECT revoked_at IS NOT NULL,"
                + " COALESCE(revoked_by_user_id, '') FROM privileged_mfa_attestation_grant WHERE id = 5"));
        }
    }

    private static List<String> reconciliationStatements() throws IOException {
        Matcher matcher = Pattern.compile("-- BEGIN RECONCILIATION\\n(.*?)-- END RECONCILIATION", Pattern.DOTALL)
            .matcher(Files.readString(Path.of("../docs/UPGRADING.md")));
        assertTrue(matcher.find(), "the runbook's reconciliation block is missing");
        return Pattern.compile(";").splitAsStream(matcher.group(1))
            .map(String::strip).filter(statement -> !statement.isEmpty()).toList();
    }

    private static void seed(Statement statement) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO organization (id, name, slug, lifecycle_state) VALUES
                (1, 'Cutover A', 'cutover-a', 'active'), (2, 'Cutover B', 'cutover-b', 'active'),
                (3, 'Teardown', 'cutover-teardown', 'tearing_down'), (4, 'Elsewhere', 'cutover-elsewhere', 'active')
            """);
        statement.executeUpdate("""
            INSERT INTO workspace (id, org_id, name, slug, lifecycle_state) VALUES
                (1, 1, 'Active A', 'active-a', 'active'), (2, 2, 'Active B', 'active-b', 'active'),
                (3, 1, 'Teardown workspace', 'teardown-w', 'tearing_down'),
                (4, 3, 'Teardown organization', 'teardown-o', 'active')
            """);
        for (int userId = 1; userId <= 12; userId++) {
            statement.executeUpdate("INSERT INTO app_user (id, username, display_name, email) VALUES ("
                + userId + ", 'cutover-" + userId + "', 'Cutover fixture', 'cutover-" + userId + "@example.com')");
            if (userId != 11) {
                statement.executeUpdate("INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES ('h"
                    + userId + "', " + userId + ", 'Cutover fixture', 'Cutover fixture')");
                for (int offset = 0; offset < 2; offset++) {
                    int credentialId = userId * 10 + offset;
                    statement.executeUpdate("INSERT INTO webauthn_credential"
                        + " (id, credential_id, user_entity_user_id, public_key) VALUES ("
                        + credentialId + ", X'" + String.format("%02x", credentialId) + "', 'h" + userId + "', X'01')");
                }
            }
        }
        statement.executeUpdate("""
            UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS',
                privileged_assured_at = '2026-09-20 12:00:00.123' WHERE id = 120
            """);
        statement.executeUpdate("""
            INSERT INTO org_member (org_id, user_id, org_role, founder) VALUES
                (1, 1, 'owner', FALSE), (1, 2, 'admin', TRUE), (2, 3, 'admin', FALSE),
                (3, 9, 'owner', FALSE), (3, 8, 'admin', TRUE)
            """);
        statement.executeUpdate("""
            INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES
                (10, 'REPORT_READ'), (20, 'MEMBER_MANAGE'), (20, 'AUDIT_READ'), (20, 'REPORT_READ'), (21, 'ROLE_MANAGE')
            """);
        statement.executeUpdate("""
            INSERT INTO workspace_member (workspace_id, user_id, role, role_id, status) VALUES
                (1, 3, 'owner', NULL, 'active'), (1, 4, 'admin', NULL, 'active'),
                (1, 5, 'member', 20, 'active'), (2, 5, 'member', 21, 'active'),
                (1, 6, 'owner', 10, 'active'), (1, 7, 'admin', NULL, 'pending'),
                (3, 8, 'owner', NULL, 'active'), (4, 9, 'owner', NULL, 'active'),
                (1, 10, 'member', NULL, 'active'), (1, 11, 'owner', NULL, 'active'),
                (1, 12, 'member', NULL, 'active')
            """);
        statement.executeUpdate("""
            INSERT INTO privileged_mfa_attestation_grant
                (id, org_id, grantor_user_id, grantee_user_id, code_digest, expires_at, created_at,
                 redeemed_at, redeemed_credential_row_id, revoked_at, revoked_by_user_id) VALUES
                (1, 1, 1, 2, REPEAT('a', 64), '2099-01-01', '2026-09-20', NULL, NULL, NULL, NULL),
                (2, 1, 1, 3, REPEAT('b', 64), '2099-01-01', '2026-09-20', '2026-09-21', 30, NULL, NULL),
                (3, 1, 1, 4, REPEAT('c', 64), '2099-01-01', '2026-09-20', NULL, NULL, '2026-09-22', 1),
                (4, 1, 1, 5, REPEAT('d', 64), '2026-09-01', '2026-08-20', NULL, NULL, NULL, NULL)
            """);
        statement.executeUpdate("""
            INSERT INTO privileged_credential_attestation
                (credential_row_id, org_id, source, grant_id, attested_at) VALUES
                (10, 1, 'FOUNDER', NULL, '2026-09-20'), (30, 1, 'GRANTOR', 2, '2026-09-21'),
                (50, 1, 'INHERITED', NULL, '2026-09-21'), (51, 2, 'GRANDFATHERED', NULL, '2026-09-21'),
                (100, 1, 'FOUNDER', NULL, '2026-09-20'), (120, 1, 'GRANTOR', 2, '2026-09-21'),
                (101, 1, 'INHERITED', NULL, '2026-09-21'), (121, 1, 'INHERITED', NULL, '2026-09-21'),
                (90, 3, 'FOUNDER', NULL, '2026-09-20'), (91, 3, 'GRANTOR', 2, '2026-09-21'),
                (10, 4, 'GRANTOR', 2, '2026-09-21'), (80, 1, 'INHERITED', NULL, '2026-09-21'),
                (60, 1, 'GRANDFATHERED', NULL, '2026-09-21')
            """);
    }

    private static void assertCutover(Connection connection) throws SQLException {
        assertEquals(List.of(List.of("1", "1", "1"), List.of("1", "2", "0"), List.of("2", "3", "0"),
                List.of("3", "8", "0"), List.of("3", "9", "1")),
            rows(connection, "SELECT org_id, user_id, founder FROM org_member ORDER BY org_id, user_id"));
        assertEquals(List.of(
                List.of("10", "1", "FOUNDER", ""), List.of("11", "1", "GRANDFATHERED", ""),
                List.of("20", "1", "GRANDFATHERED", ""), List.of("21", "1", "GRANDFATHERED", ""),
                List.of("30", "1", "GRANTOR", "2"), List.of("30", "2", "GRANDFATHERED", ""),
                List.of("31", "1", "GRANDFATHERED", ""), List.of("31", "2", "GRANDFATHERED", ""),
                List.of("40", "1", "GRANDFATHERED", ""), List.of("41", "1", "GRANDFATHERED", ""),
                List.of("50", "1", "INHERITED", ""), List.of("50", "2", "GRANDFATHERED", ""),
                List.of("51", "1", "GRANDFATHERED", ""), List.of("51", "2", "GRANDFATHERED", "")),
            rows(connection, "SELECT credential_row_id, org_id, source, COALESCE(grant_id, '')"
                + " FROM privileged_credential_attestation ORDER BY credential_row_id, org_id"));
        assertEquals(List.of(List.of("2026-09-20 00:00:00"), List.of("2026-09-21 00:00:00"),
                List.of("2026-09-21 00:00:00"), List.of("2026-09-21 00:00:00")),
            rows(connection, "SELECT DATE_FORMAT(attested_at, '%Y-%m-%d %H:%i:%s')"
                + " FROM privileged_credential_attestation WHERE source <> 'GRANDFATHERED'"
                + " OR credential_row_id = 51 AND org_id = 2 ORDER BY credential_row_id, org_id"));
        assertEquals(List.of(List.of("10", "1")), rows(connection,
            "SELECT COUNT(*), COUNT(DISTINCT attested_at) FROM privileged_credential_attestation"
                + " WHERE source = 'GRANDFATHERED' AND NOT (credential_row_id = 51 AND org_id = 2)"));
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT id, redeemed_at, redeemed_credential_row_id, revoked_at, revoked_by_user_id"
                    + " FROM privileged_mfa_attestation_grant ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("id"));
            assertNotNull(rows.getTimestamp("revoked_at"));
            assertNull(rows.getObject("revoked_by_user_id"));
            assertNull(rows.getObject("redeemed_at"));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt("id"));
            assertEquals("2026-09-21 00:00:00.0", rows.getTimestamp("redeemed_at").toString());
            assertEquals(30, rows.getInt("redeemed_credential_row_id"));
            assertNull(rows.getObject("revoked_at"));
            assertTrue(rows.next());
            assertEquals(3, rows.getInt("id"));
            assertEquals("2026-09-22 00:00:00.0", rows.getTimestamp("revoked_at").toString());
            assertEquals(1, rows.getInt("revoked_by_user_id"));
            assertTrue(rows.next());
            assertEquals(4, rows.getInt("id"));
            assertNotNull(rows.getTimestamp("revoked_at"));
            assertNull(rows.getObject("revoked_by_user_id"));
            assertFalse(rows.next());
        }
    }

    private Flyway migrate(String location) {
        Flyway flyway = flyway(location);
        flyway.migrate();
        return flyway;
    }

    private Flyway flyway(String location) {
        return Flyway.configure().dataSource(scratchCatalog.url(), username, password)
            .locations(location).baselineOnMigrate(true).baselineVersion("224").target("225").load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(scratchCatalog.url(), username, password);
    }

    private static Set<String> populationPairs(Connection connection, String block) throws SQLException {
        Set<String> pairs = new TreeSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(block)) {
            while (rows.next()) {
                pairs.add(rows.getInt("user_id") + ":" + rows.getInt("org_id"));
            }
        }
        return pairs;
    }

    private static List<List<List<String>>> snapshot(Connection connection) throws SQLException {
        return List.of(rows(connection, "SELECT * FROM org_member ORDER BY org_id, user_id"),
            rows(connection, "SELECT * FROM privileged_credential_attestation ORDER BY credential_row_id, org_id"),
            rows(connection, "SELECT * FROM privileged_mfa_attestation_grant ORDER BY id"),
            rows(connection, "SELECT * FROM webauthn_credential ORDER BY id"));
    }

    private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    row.add(rows.getString(column));
                }
                result.add(row);
            }
        }
        return result;
    }
}

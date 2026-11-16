package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.support.PersonVisibilityParity;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.util.LikePattern;

/** Exercises real list/count/global predicates with missing projections and explicit sharing scopes. */
class SearchNullProjectionIntegrationTest {
    private static final int WORKSPACE = 11;
    private static final int FOREIGN_WORKSPACE = 22;
    private static final List<String> TABLES = List.of("company", "person", "company_share", "person_share");

    private final TenantContext context = new TenantContext();
    private MySqlScratchCatalog catalog;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSession session;
    private String orgWorkspaceIdsJson = "[11]";

    @BeforeEach
    void cloneEmptyTenantTables() throws Exception {
        String configuredUrl = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUrl != null && username != null && password != null, "Database credentials required");
        String source = TenantRoutingConfig.databaseFromJdbcUrl(configuredUrl);
        if (source == null || !source.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("The JDBC URL must name a physical source catalog");
        }
        ensureSourceTables(configuredUrl, username, password, source);
        catalog = MySqlScratchCatalog.create(configuredUrl, username, password,
            "connex_search_null_", "utf8mb4", "utf8mb4_0900_ai_ci");
        try (Connection connection = DriverManager.getConnection(catalog.url(), username, password);
                var statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute("CREATE TABLE `" + table + "` LIKE `" + source + "`.`" + table + "`");
            }
        }
        Flyway.configure().dataSource(catalog.url(), username, password)
            .locations("classpath:db/migration/tenant").baselineOnMigrate(true).baselineVersion("243")
            .target("244").load().migrate();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(catalog.url());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(2);
        dataSource.setConnectionTimeout(5000);
        jdbc = new JdbcTemplate(dataSource);
        Configuration configuration = new Configuration(new Environment(
            "search-null-projections", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
        for (String resource : List.of("mappers/CompanyMapper.xml", "mappers/PersonMapper.xml", "mappers/TagMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        context.set(WORKSPACE, 1, 7, "member", catalog.name());
        for (int index = 1; index <= 25; index++) {
            insertBoth(index, WORKSPACE, "AAAunrelated" + index, "03-1000-0000");
        }
        assertKeysRemainNull();
    }

    @AfterEach
    void dropOwnedScratchCatalog() throws SQLException {
        context.clear();
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            try {
                if (dataSource != null) {
                    dataSource.close();
                }
            } finally {
                if (catalog != null) {
                    catalog.close();
                }
            }
        }
    }

    @Test
    void nameVariantsMatchNullProjectionsBeforePaginationAndWithoutRepair() throws SQLException {
        insertBoth(101, WORKSPACE, "山田 太郎", null);
        insertBoth(102, WORKSPACE, "山田太郎", null);
        insertBoth(103, WORKSPACE, "ﾔﾏﾀﾞ\u3000ﾀﾛｳ", null);
        insertBoth(104, WORKSPACE, "ヤマダ タロウ", null);
        insertBoth(105, WORKSPACE, "やまだたろう", null);
        insertBoth(106, WORKSPACE, "ＡＣＭＥ\u3000１２３", null);
        insertBoth(107, WORKSPACE, "ACME123", null);
        for (String query : List.of("山田太郎", "山田 太郎", "山田\u3000太郎")) {
            assertBothMatch(query, Set.of(101, 102));
        }
        for (String query : List.of("ﾔﾏﾀﾞ ﾀﾛｳ", "ヤマダタロウ", "やまだたろう")) {
            assertBothMatch(query, Set.of(103, 104, 105));
        }
        for (String query : List.of("ＡＣＭＥ１２３", "ACME 123", "acme123")) {
            assertBothMatch(query, Set.of(106, 107));
        }
        assertBothMatch("%", Set.of());
        assertBothMatch("_", Set.of());
        assertKeysRemainNull();
    }

    @Test
    void phoneVariantsMatchNullProjectionsInBothDirectionsAndKeepSuffixes() throws SQLException {
        insertBoth(101, WORKSPACE, "ZZZ phone target", null);
        List<String> variants = List.of("090-9684-6208", "+81 90 9684 6208", "０９０（９６８４）６２０８",
            "090・9684・6208", "090ー9684ー6208", "090\u00a09684\u20076208",
            "+81\u202f(0)90\u30009684\u20096208", "０９０－９６８４－６２０８ 内線１２３",
            "+81(0)90-9684-6208;ext=123", "090-9684-6208 ext. 123", "090-9684-6208 x123");
        for (String stored : variants) {
            updateBothPhones(101, stored);
            for (String query : List.of("09096846208", "+81-90-9684-6208", "96846208")) {
                assertBothMatch(query, Set.of(101));
            }
        }
        updateBothPhones(101, "+81-90-9684-6208");
        for (String query : variants) {
            assertBothMatch(query, Set.of(101));
        }
        updateBothPhones(101, "+1 (909) 684-6208 x123");
        assertBothMatch("9096846208", Set.of(101));
        assertBothMatch("+1 909 684 6208", Set.of(101));
        updateBothPhones(101, "090-0000-0000");
        assertBothMatch("09000000000", Set.of(101));
        updateBothPhones(101, "+44(0)20 7946 0018");
        assertBothMatch("+44 20 7946 0018", Set.of(101));
        updateBothPhones(101, "+44 20 7946 0018");
        assertBothMatch("+44(0)20 7946 0018", Set.of(101));
        assertKeysRemainNull();
    }

    @Test
    void foreignNullProjectionRowsStayInvisibleEvenWithStaleCrossOrgShares() throws SQLException {
        insertBoth(101, FOREIGN_WORKSPACE, "外部ﾔﾏﾀﾞ\u3000ﾀﾛｳ", "+81-90-9684-6208");
        jdbc.update("INSERT INTO person_share (person_id, workspace_id) VALUES (?, ?)", 101, WORKSPACE);
        jdbc.update("INSERT INTO company_share (company_id, workspace_id) VALUES (?, ?)", 101, WORKSPACE);
        for (String query : List.of("外部やまだたろう", "外部ヤマダタロウ", "09096846208", "０９０９６８４６２０８", "96846208")) {
            assertBothMatch(query, Set.of());
        }
        assertKeysRemainNull();
        fillBothKeys(101, FOREIGN_WORKSPACE, "外部やまだたろう", "819096846208");
        for (String query : List.of("外部やまだたろう", "外部ヤマダタロウ", "09096846208", "０９０９６８４６２０８", "96846208")) {
            assertBothResults(query, Set.of(), FOREIGN_WORKSPACE);
        }
    }

    /** The explicit scope models workspaces 11 and 22 in organization 1; a share is still required. */
    @Test
    void sameOrganizationSharesRetainSourceOwnershipWithNullAndFilledProjections() throws SQLException {
        orgWorkspaceIdsJson = "[11,22]";
        insertBoth(101, FOREIGN_WORKSPACE, "山田 太郎", "+81-90-9684-6208");
        List<String> queries = List.of("山田太郎", "09096846208", "+81 90 9684 6208", "96846208");
        for (String query : queries) {
            assertBothMatch(query, Set.of());
        }
        jdbc.update("INSERT INTO person_share (person_id, workspace_id) VALUES (?, ?)", 101, WORKSPACE);
        jdbc.update("INSERT INTO company_share (company_id, workspace_id) VALUES (?, ?)", 101, WORKSPACE);
        assertKeysRemainNull();
        for (String query : queries) {
            assertBothResults(query, Set.of(101), FOREIGN_WORKSPACE);
        }
        assertKeysRemainNull();
        fillBothKeys(101, FOREIGN_WORKSPACE, "山田太郎", "819096846208");
        for (String query : queries) {
            assertBothResults(query, Set.of(101), FOREIGN_WORKSPACE);
        }
        jdbc.update("DELETE FROM person_share WHERE person_id = ? AND workspace_id = ?", 101, WORKSPACE);
        jdbc.update("DELETE FROM company_share WHERE company_id = ? AND workspace_id = ?", 101, WORKSPACE);
        for (String query : queries) {
            assertBothResults(query, Set.of(), FOREIGN_WORKSPACE);
        }
    }

    private void assertBothMatch(String raw, Set<Integer> expected) {
        assertKeysRemainNull();
        assertBothResults(raw, expected, WORKSPACE);
        assertKeysRemainNull();
    }

    private void assertBothResults(String raw, Set<Integer> expected, int ownerWorkspace) {
        assertPersonMatches(raw, expected, ownerWorkspace);
        assertCompanyMatches(raw, expected, ownerWorkspace);
    }

    private void assertPersonMatches(String raw, Set<Integer> expected, int ownerWorkspace) {
        Map<String, Object> parameters = parameters(raw);
        Long count = session.selectOne("ooo.klae.connex.backend.mappers.PersonMapper.countPersons", parameters);
        assertNotNull(count);
        assertEquals(expected.size(), count.longValue(), () -> sourceMetadata("person"));
        Set<Integer> paged = new HashSet<>();
        for (int offset = 0; offset < Math.max(1, expected.size()); offset++) {
            parameters.put("offset", offset);
            List<Person> page = session.selectList("ooo.klae.connex.backend.mappers.PersonMapper.getPersonsPage", parameters);
            assertEquals(expected.isEmpty() ? 0 : 1, page.size(), () -> sourceMetadata("person"));
            for (Person row : page) {
                assertEquals(ownerWorkspace, row.getWorkspaceId(), () -> sourceMetadata("person")
                    + " returnedId=" + row.getId() + " returnedWorkspace=" + row.getWorkspaceId());
            }
            page.stream().map(Person::getId).forEach(paged::add);
        }
        assertEquals(expected, paged, () -> sourceMetadata("person"));
        List<Person> global = session.selectList("ooo.klae.connex.backend.mappers.PersonMapper.search", parameters(raw));
        assertEquals(expected, new HashSet<>(global.stream().map(Person::getId).toList()), () -> sourceMetadata("person"));
        for (Person row : global) {
            assertEquals(ownerWorkspace, row.getWorkspaceId(), () -> sourceMetadata("person")
                + " returnedId=" + row.getId() + " returnedWorkspace=" + row.getWorkspaceId());
        }
    }

    private void assertCompanyMatches(String raw, Set<Integer> expected, int ownerWorkspace) {
        Map<String, Object> parameters = parameters(raw);
        Long count = session.selectOne("ooo.klae.connex.backend.mappers.CompanyMapper.countCompanies", parameters);
        assertNotNull(count);
        assertEquals(expected.size(), count.longValue(), () -> sourceMetadata("company"));
        Set<Integer> paged = new HashSet<>();
        for (int offset = 0; offset < Math.max(1, expected.size()); offset++) {
            parameters.put("offset", offset);
            List<Company> page = session.selectList("ooo.klae.connex.backend.mappers.CompanyMapper.getCompaniesPage", parameters);
            assertEquals(expected.isEmpty() ? 0 : 1, page.size(), () -> sourceMetadata("company"));
            for (Company row : page) {
                assertEquals(ownerWorkspace, row.getWorkspaceId(), () -> sourceMetadata("company")
                    + " returnedId=" + row.getId() + " returnedWorkspace=" + row.getWorkspaceId());
            }
            page.stream().map(Company::getId).forEach(paged::add);
        }
        assertEquals(expected, paged, () -> sourceMetadata("company"));
        List<Company> global = session.selectList("ooo.klae.connex.backend.mappers.CompanyMapper.search", parameters(raw));
        assertEquals(expected, new HashSet<>(global.stream().map(Company::getId).toList()), () -> sourceMetadata("company"));
        for (Company row : global) {
            assertEquals(ownerWorkspace, row.getWorkspaceId(), () -> sourceMetadata("company")
                + " returnedId=" + row.getId() + " returnedWorkspace=" + row.getWorkspaceId());
        }
    }

    private Map<String, Object> parameters(String raw) {
        Map<String, Object> parameters = PersonVisibilityParity.parameters();
        parameters.put("workspaceId", WORKSPACE);
        parameters.put("orgWorkspaceIdsJson", orgWorkspaceIdsJson);
        parameters.put("query", LikePattern.containing(raw));
        parameters.put("warmth", null);
        parameters.put("memberScope", MemberScope.allTeam());
        parameters.put("industry", List.of());
        parameters.put("noIndustry", false);
        parameters.put("ids", List.of());
        parameters.put("limit", 1);
        parameters.put("offset", 0);
        return parameters;
    }

    private void insertBoth(int id, int workspace, String name, String phone) throws SQLException {
        for (String table : List.of("person", "company")) {
            try (Connection connection = dataSource.getConnection();
                    var insert = connection.prepareStatement("INSERT INTO " + table
                        + " (id, workspace_id, name, phone) VALUES (?, ?, CONVERT(? USING utf8mb4), CONVERT(? USING utf8mb4))")) {
                insert.setInt(1, id);
                insert.setInt(2, workspace);
                insert.setBytes(3, name.getBytes(StandardCharsets.UTF_8));
                insert.setBytes(4, phone == null ? null : phone.getBytes(StandardCharsets.UTF_8));
                assertEquals(1, insert.executeUpdate());
            }
        }
    }

    private void updateBothPhones(int id, String phone) throws SQLException {
        for (String table : List.of("person", "company")) {
            try (Connection connection = dataSource.getConnection();
                    var update = connection.prepareStatement("UPDATE " + table
                        + " SET phone = CONVERT(? USING utf8mb4) WHERE workspace_id = ? AND id = ?")) {
                update.setBytes(1, phone.getBytes(StandardCharsets.UTF_8));
                update.setInt(2, WORKSPACE);
                update.setInt(3, id);
                assertEquals(1, update.executeUpdate());
            }
        }
        assertKeysRemainNull();
    }

    private void assertKeysRemainNull() {
        for (String table : List.of("person", "company")) {
            Integer filled = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE search_name IS NOT NULL OR search_phone IS NOT NULL", Integer.class);
            assertEquals(0, Objects.requireNonNull(filled).intValue(), table);
        }
    }

    private void fillBothKeys(int id, int workspace, String nameKey, String phoneKey) throws SQLException {
        for (String table : List.of("person", "company")) {
            try (Connection connection = dataSource.getConnection();
                    var update = connection.prepareStatement("UPDATE " + table
                        + " SET search_name = CONVERT(? USING utf8mb4), search_phone = ?,"
                        + " search_key_revision = search_key_revision + 1 WHERE workspace_id = ? AND id = ?")) {
                update.setBytes(1, nameKey.getBytes(StandardCharsets.UTF_8));
                update.setString(2, phoneKey);
                update.setInt(3, workspace);
                update.setInt(4, id);
                assertEquals(1, update.executeUpdate());
            }
            assertEquals(1, Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE id = ? AND workspace_id = ? AND search_name IS NOT NULL AND search_phone IS NOT NULL",
                Integer.class, id, workspace)).intValue(), () -> sourceMetadata(table));
        }
    }

    private String sourceMetadata(String table) {
        return table + " viewerWorkspace=" + WORKSPACE + " viewerOrg=1 orgWorkspaceIds=" + orgWorkspaceIdsJson
            + " sources=" + jdbc.queryForList("SELECT id, workspace_id FROM " + table + " ORDER BY id");
    }

    private static void ensureSourceTables(String url, String username, String password, String source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                var tables = connection.getMetaData().getTables(source, null, "person", new String[] {"TABLE"})) {
            if (tables.next()) {
                return;
            }
        }
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
            .callbacks(new AuditLogV126MigrationCallback()).baselineOnMigrate(true).baselineVersion("0")
            .target("243").load().migrate();
    }
}

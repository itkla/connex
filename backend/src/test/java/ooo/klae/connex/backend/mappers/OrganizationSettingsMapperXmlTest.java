package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Mapper-level immutability, authorization ceiling, and bounded-query contract. */
class OrganizationSettingsMapperXmlTest {

    @Test
    void workspaceIdentityWriteCannotMutateIdOrgOrSlug() throws Exception {
        Configuration configuration = configuration();
        BoundSql update = boundSql(
            configuration,
            WorkspaceMapper.class,
            "updateIdentity",
            Map.of("workspaceId", 7, "name", "Renamed", "timezone", "UTC"));
        String sql = compact(update.getSql());

        assertTrue(sql.contains(
            "SET name = ?, timezone = ?, identity_version = identity_version + 1"));
        assertTrue(sql.contains("WHERE id = ? AND lifecycle_state = 'active'"));
        assertFalse(sql.contains("slug ="));
        assertFalse(sql.contains("org_id ="));
        assertEquals(3, update.getParameterMappings().size());

        String lock = compact(boundSql(
            configuration,
            WorkspaceMapper.class,
            "lockActiveIdentity",
            Map.of("workspaceId", 7)).getSql());
        assertTrue(lock.endsWith("FOR UPDATE"));
    }

    @Test
    void organizationRenameCannotMutateSlugAndUsesActiveLockedRoot() throws Exception {
        Configuration configuration = configuration();
        String update = compact(boundSql(
            configuration,
            OrganizationMapper.class,
            "updateName",
            Map.of("id", 3, "name", "Renamed")).getSql());
        String lock = compact(boundSql(
            configuration,
            OrganizationMapper.class,
            "lockActiveIdentity",
            Map.of("id", 3)).getSql());

        assertTrue(update.contains("SET name = ?, identity_version = identity_version + 1"));
        assertTrue(update.contains("WHERE id = ? AND lifecycle_state = 'active'"));
        assertFalse(update.contains("slug ="));
        assertTrue(lock.contains("lifecycle_state = 'active'"));
        assertTrue(lock.endsWith("FOR UPDATE"));
    }

    @Test
    void workspaceMembershipProjectionCarriesBothIdentityVersions() throws Exception {
        Configuration configuration = configuration();
        String memberships = compact(boundSql(
            configuration,
            WorkspaceMapper.class,
            "getMembershipsForUser",
            Map.of("userId", 7)).getSql());
        String organization = compact(boundSql(
            configuration,
            OrganizationMapper.class,
            "getActiveById",
            Map.of("id", 3)).getSql());

        assertTrue(memberships.contains("w.identity_version AS identityVersion"));
        assertTrue(memberships.contains("o.identity_version AS orgIdentityVersion"));
        assertTrue(organization.contains("identity_version AS identityVersion"));
    }

    @Test
    void layoutMembershipQueryIsActorAuthorizedOrgBoundAndPerWorkspaceBounded() throws Exception {
        Configuration configuration = configuration();
        BoundSql layout = boundSql(
            configuration,
            WorkspaceMapper.class,
            "findLayoutMemberships",
            Map.of(
                "orgId", 3,
                "actorId", 7,
                "workspaceIds", List.of(11, 12),
                "memberLimit", 100));
        String sql = compact(layout.getSql());

        assertTrue(sql.contains("WHERE w.org_id = ?"));
        assertTrue(sql.contains("w.id IN ( ? , ? )"));
        assertTrue(sql.contains("actor_membership.user_id = ?"));
        assertTrue(sql.contains("actor_membership.status = 'active'"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY wm.workspace_id"));
        assertTrue(sql.contains("WHERE memberRow <= ?"));
        assertFalse(resource("mappers/WorkspaceMapper.xml").contains("${"));
        assertEquals(6, layout.getParameterMappings().size());
    }

    @Test
    void layoutNodeAndAuthorityQueriesUseExclusiveCursorsAndLimits() throws Exception {
        Configuration configuration = configuration();
        String workspaces = compact(boundSql(
            configuration,
            WorkspaceMapper.class,
            "findActiveByOrgIdPage",
            Map.of("orgId", 3, "afterWorkspaceId", 11, "limit", 51)).getSql());
        String authorities = compact(boundSql(
            configuration,
            OrgMemberMapper.class,
            "findLayoutAuthorityMemberships",
            Map.of("orgId", 3, "afterUserId", 7, "limit", 51)).getSql());

        assertTrue(workspaces.contains("w.id > ?"));
        assertTrue(workspaces.endsWith("LIMIT ?"));
        assertTrue(authorities.contains("om.user_id > ?"));
        assertTrue(authorities.endsWith("LIMIT ?"));
    }

    @Test
    void timezoneMigrationLeavesExistingRowsNull() throws Exception {
        String migration = resource(
            "db/migration/control/V146__workspace_timezone.sql");
        String sql = compact(migration);

        assertTrue(sql.contains("ALTER TABLE workspace"));
        assertTrue(sql.contains("ADD COLUMN timezone VARCHAR(64)"));
        assertTrue(sql.contains("NULL AFTER slug"));
        assertFalse(sql.toUpperCase().contains("DEFAULT"));
        assertEquals(1, migration.chars().filter(character -> character == ';').count());
    }

    @Test
    void identityVersionMigrationSeedsBothIdentityRootsAtZero() throws Exception {
        String migration = resource("db/migration/control/V147__identity_versions.sql");
        String sql = compact(migration);

        assertTrue(sql.contains("ALTER TABLE workspace"));
        assertTrue(sql.contains("ALTER TABLE organization"));
        assertEquals(2, count(sql, "ADD COLUMN identity_version BIGINT NOT NULL DEFAULT 0"));
        assertEquals(2, migration.chars().filter(character -> character == ';').count());
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.dto");
        for (String resource : List.of(
                "mappers/WorkspaceMapper.xml",
                "mappers/OrganizationMapper.xml",
                "mappers/OrgMemberMapper.xml")) {
            try (InputStream input = OrganizationSettingsMapperXmlTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static BoundSql boundSql(
            Configuration configuration,
            Class<?> mapper,
            String statement,
            Object parameters) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
            .getBoundSql(parameters);
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = OrganizationSettingsMapperXmlTest.class
                .getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static int count(String value, String pattern) {
        return value.split(java.util.regex.Pattern.quote(pattern), -1).length - 1;
    }
}

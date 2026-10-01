package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Owns fresh public API fixtures and aggregates cleanup failures without abandoning later cleanup. */
final class PublicApiFixtureSupport {
    private final OrganizationMapper organizationMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final String password;
    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<String> scratchCatalogs = new ArrayList<>();

    PublicApiFixtureSupport(OrganizationMapper organizationMapper, WorkspaceMapper workspaceMapper,
            UserMapper userMapper, PasswordEncoder passwordEncoder, JdbcTemplate jdbcTemplate,
            String password) {
        this.organizationMapper = organizationMapper;
        this.workspaceMapper = workspaceMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.password = password;
    }

    Workspace newWorkspace(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Public API " + label + " " + suffix);
        organization.setSlug("public-api-" + label + "-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Public API " + label + " " + suffix);
        workspace.setSlug("public-api-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    User newMember(Workspace workspace, String role, String label) {
        User user = newUser(label);
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
    }

    User newUser(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("public_api_" + label + "_" + suffix);
        user.setDisplayName("Public API " + label);
        user.setEmail(label + "-" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    void grantApiManager(Workspace workspace, User user, String label) {
        jdbcTemplate.update(
            "INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)",
            workspace.getId(),
            "API manager " + label + " " + UUID.randomUUID().toString().substring(0, 8));
        Integer roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM workspace_role WHERE workspace_id = ? ORDER BY id DESC LIMIT 1",
            Integer.class,
            workspace.getId());
        assertNotNull(roleId);
        jdbcTemplate.update(
            "INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?), (?, ?)",
            roleId,
            "API_CREDENTIAL_MANAGE",
            roleId,
            "REPORT_READ");
        jdbcTemplate.update(
            "UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?",
            roleId,
            workspace.getId(),
            user.getId());
    }

    void cleanUp(boolean requireSharedPlacement) {
        Throwable cleanupFailure = requireSharedPlacement
            ? attempt(null, this::assertNoDedicatedPlacementLeaks) : null;
        for (int workspaceId : workspaceIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM api_credential WHERE workspace_id = ?", workspaceId));
        }
        for (int organizationId : organizationIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM org_placement WHERE org_id = ?", organizationId));
        }
        for (int workspaceId : workspaceIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM workspace WHERE id = ?", workspaceId));
        }
        for (int organizationId : organizationIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?", organizationId));
        }
        for (int userId : userIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?", userId));
        }
        for (String catalog : scratchCatalogs) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.execute(
                "DROP DATABASE IF EXISTS `" + identifier(catalog) + "`"));
        }
        cleanupFailure = attempt(cleanupFailure, this::assertControlPlaneCleanupComplete);
        cleanupFailure = attempt(cleanupFailure, this::assertScratchCatalogsRemoved);
        scratchCatalogs.clear();
        workspaceIds.clear();
        organizationIds.clear();
        userIds.clear();
        if (cleanupFailure != null) {
            rethrow(cleanupFailure);
        }
    }

    private void assertNoDedicatedPlacementLeaks() {
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement "
                    + "WHERE org_id = ? AND placement_mode = 'dedicated_database'",
                Integer.class,
                organizationId),
                "Public API fixture leaked a dedicated org_placement row for organization "
                    + organizationId);
        }
    }

    private void assertControlPlaneCleanupComplete() {
        for (int workspaceId : workspaceIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
                Integer.class,
                workspaceId),
                "Public API fixture leaked api_credential rows for workspace " + workspaceId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?",
                Integer.class,
                workspaceId),
                "Public API fixture leaked workspace " + workspaceId);
        }
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement WHERE org_id = ?",
                Integer.class,
                organizationId),
                "Public API fixture leaked org_placement rows for organization "
                    + organizationId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE id = ?",
                Integer.class,
                organizationId),
                "Public API fixture leaked organization " + organizationId);
        }
        for (int userId : userIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ?",
                Integer.class,
                userId),
                "Public API fixture leaked app_user " + userId);
        }
    }

    private static Throwable attempt(Throwable previous, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (previous == null) {
                return failure;
            }
            previous.addSuppressed(failure);
        }
        return previous;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    String createScratchCatalog(String label) {
        String catalog = MySqlScratchCatalog.uniqueName("cnx_public_api_" + label + "_");
        scratchCatalogs.add(catalog);
        jdbcTemplate.execute("CREATE DATABASE `" + identifier(catalog)
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        return catalog;
    }

    private void assertScratchCatalogsRemoved() {
        for (String catalog : scratchCatalogs) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                Integer.class, catalog));
        }
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid test catalog identifier");
        }
        return value;
    }
}

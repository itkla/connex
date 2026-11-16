package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.support.AiOutputCachePlaneFixture;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises the real restriction service after independently committed workspace/cache creation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiOutputCacheRestrictionScopeIntegrationTest {
    @Autowired private PersonService persons;
    @Autowired private RecordVisibilityScopeControlAccess scopes;
    @Autowired private AiOutputCacheMapper cache;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantWorkScope workScope;
    @Autowired private ooo.klae.connex.backend.ai.AiRestrictionEpoch restrictionEpoch;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @MockitoBean private AuditService auditService;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void restrictionPurgesWorkspacesCommittedAfterPreparedOrMemoizedVisibilityReads(
            boolean provisionCeased, boolean preparedEntry)
            throws Exception {
        try (var fixture = new AiOutputCachePlaneFixture(false, 2)) {
            for (int workspace : fixture.workspaceIds) {
                if (workspace != fixture.workspaceIds.get(5)) {
                    TestEntityFixtures.seedExistingWorkspacePrivacy(fixture.control, workspace);
                }
            }
            User actor = new User();
            actor.setId(fixture.userId);
            actor.setUsername("cache-restriction-actor");
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
            tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "owner", null);
            int deletedWorkspace = preparedEntry ? fixture.addWorkspace(fixture.orgId) : 0;
            var prepared = preparedEntry
                ? workScope.prepareWorkspaceScopes(fixture.workspaceId, fixture.orgId, null) : null;
            if (preparedEntry) {
                assertTrue(prepared.requireSharing().recordVisibilityScope().workspaceIds().contains(deletedWorkspace));
                tenantContext.setWithWorkspaceScopes(fixture.workspaceId, fixture.orgId, fixture.userId,
                    "owner", null, prepared);
                fixture.control.update("DELETE FROM workspace_member WHERE workspace_id = ?", deletedWorkspace);
                fixture.control.update("DELETE FROM workspace WHERE id = ?", deletedWorkspace);
            }
            try {
                var transaction = new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.executeWithoutResult(status -> {
                    Connection original = DataSourceUtils.getConnection(dataSource);
                    var earlierScope = scopes.getForWorkspace(fixture.workspaceId);
                    int sibling;
                    try {
                        sibling = fixture.addWorkspace(fixture.orgId);
                        TestEntityFixtures.seedExistingWorkspacePrivacy(fixture.control, sibling);
                        fixture.addCache(sibling, "intro.rationale:en", fixture.personId, 0, true);
                    } catch (java.sql.SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                    assertFalse(earlierScope.workspaceIds().contains(sibling));
                    assertSame(earlierScope, scopes.getForWorkspace(fixture.workspaceId));

                    long siblingEpoch = restrictionEpoch.current(sibling);
                    if (preparedEntry) {
                        assertFalse(scopes.getFreshForWorkspace(fixture.workspaceId).workspaceIds().contains(deletedWorkspace));
                    }
                    var result = persons.updateProcessingRestrictions(fixture.personId, !provisionCeased, provisionCeased);

                    assertTrue(result.getSuspendedAt() != null || result.getProvisionCeasedAt() != null);
                    assertNull(cache.getBySubject(sibling, "intro.rationale:en", fixture.personId, 0));
                    assertSame(earlierScope, scopes.getForWorkspace(fixture.workspaceId));
                    assertSame(prepared, tenantContext.getWorkspaceScopes());
                    assertTrue(restrictionEpoch.current(sibling) > siblingEpoch);
                    assertEquals(1, new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                        "SELECT COUNT(*) FROM ai_assistant_privacy_operation_workspace w "
                            + "JOIN ai_assistant_privacy_operation o ON o.organization_id = w.organization_id "
                            + "AND o.operation_key = w.operation_key "
                            + "WHERE w.organization_id = ? AND w.workspace_id = ? AND o.reason = 'restriction'",
                        Integer.class, fixture.orgId, sibling));
                    assertSame(original, DataSourceUtils.getConnection(dataSource));
                    assertEquals(fixture.defaultCatalog, catalog(original));
                    assertEquals(fixture.cacheCases.stream().filter(row -> !row.deleted()).map(row -> row.id()).sorted().toList(),
                        new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(
                            "SELECT id FROM ai_output_cache WHERE workspace_id IN ("
                                + fixture.workspaceIds.stream().map(String::valueOf)
                                    .collect(java.util.stream.Collectors.joining(",")) + ") ORDER BY id", Integer.class));
                    status.setRollbackOnly();
                });
                assertEquals(fixture.cacheCases.size(), fixture.rows().size());
                assertEquals(1, fixture.control.queryForObject("SELECT COUNT(*) FROM person WHERE id = ? "
                    + "AND suspended_at IS NULL AND provision_ceased_at IS NULL", Integer.class, fixture.personId));
                assertEquals(3, fixture.control.queryForObject(
                    "SELECT COUNT(*) FROM person_share WHERE person_id = ?", Integer.class, fixture.personId));
            } finally {
                tenantContext.clear();
                SecurityContextHolder.clearContext();
            }
        }
    }

    private static String catalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

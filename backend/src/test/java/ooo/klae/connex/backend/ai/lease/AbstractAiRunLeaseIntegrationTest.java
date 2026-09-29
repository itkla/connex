package ooo.klae.connex.backend.ai.lease;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Shared real-database fixture for the run-lease drills: two throwaway organizations and
 * workspaces, a member in the first, and the transaction template the caller-joining lease methods
 * require.
 *
 * <p>The second workspace exists so that every statement capable of reaching more than one row can
 * be asserted against a neighbouring tenant holding the same subject id and epoch.
 * {@code TenantScopeArchTest} documents its own {@code #{workspaceId}} check as a presence, not a
 * placement, heuristic — a binding in an unrelated subquery would still pass it — and names runtime
 * cross-workspace coverage as the layer that catches the rest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractAiRunLeaseIntegrationTest {

    static final String CHAT_TURN = "chat_turn";

    @Autowired AiProperties aiProperties;
    @Autowired AiRunLeaseService leaseService;
    @Autowired AiRunLeaseRegistry leaseRegistry;
    @Autowired AiRunLeaseIdentity leaseIdentity;
    @Autowired AiRunLeaseMapper leaseMapper;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired WorkspaceMapper workspaceMapper;
    @Autowired UserMapper userMapper;
    @Autowired TenantContext tenantContext;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    Organization organization;
    Workspace workspace;
    Organization neighbourOrganization;
    Workspace neighbourWorkspace;
    User member;
    TransactionTemplate transactions;

    @BeforeEach
    void createTenantFixture() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("AI run lease " + unique);
        organization.setSlug("ai-run-lease-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("AI run lease " + unique);
        workspace.setSlug("ai-run-lease-" + unique);
        workspaceMapper.insert(workspace);

        neighbourOrganization = new Organization();
        neighbourOrganization.setName("AI run lease neighbour " + unique);
        neighbourOrganization.setSlug("ai-run-lease-neighbour-" + unique);
        organizationMapper.insert(neighbourOrganization);

        neighbourWorkspace = new Workspace();
        neighbourWorkspace.setOrgId(neighbourOrganization.getId());
        neighbourWorkspace.setName("AI run lease neighbour " + unique);
        neighbourWorkspace.setSlug("ai-run-lease-neighbour-" + unique);
        workspaceMapper.insert(neighbourWorkspace);

        member = new User();
        member.setUsername("ai-run-lease-" + unique);
        member.setDisplayName("AI run lease " + unique);
        member.setEmail("ai-run-lease-" + unique + "@example.com");
        member.setPasswordHash("hash-" + unique);
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "owner");

        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        pinTenant();
    }

    @AfterEach
    void removeTenantFixture() {
        tenantContext.clear();
        if (neighbourWorkspace != null) {
            jdbcTemplate.update(
                    "DELETE FROM ai_run_lease WHERE workspace_id = ?", neighbourWorkspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", neighbourWorkspace.getId());
        }
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM ai_run_lease WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
    }

    void pinTenant() {
        tenantContext.set(
                workspace.getId(), organization.getId(), member.getId(), "owner", null);
    }

    AiRunLeaseKey key(AiRunLeaseSubject subject, long subjectId) {
        return new AiRunLeaseKey(workspace.getId(), subject, subjectId);
    }

    AiRunLeaseKey neighbourKey(AiRunLeaseSubject subject, long subjectId) {
        return new AiRunLeaseKey(neighbourWorkspace.getId(), subject, subjectId);
    }

    AiRunLease acquire(AiRunLeaseKey key) {
        return acquire(key, freshGuard());
    }

    AiRunLease acquire(AiRunLeaseKey key, AiRunLeaseGuard guard) {
        return transactions.execute(
                status -> leaseService.acquireInCurrentTransaction(key, guard));
    }

    /**
     * Builds the ownership flag a claim anchors, for the drills that only care about the row.
     *
     * @return a guard whose lifetime matches the configured lease lifetime
     */
    AiRunLeaseGuard freshGuard() {
        return new AiRunLeaseGuard(aiProperties.getRunLeaseTtl());
    }

    boolean release(AiRunLeaseKey key) {
        return Boolean.TRUE.equals(
                transactions.execute(status -> leaseService.releaseHeldInCurrentTransaction(key)));
    }

    /**
     * Takes a lease over for settlement inside a transaction, as every settler must.
     *
     * <p>{@code takeOverForSettlement} declares {@code MANDATORY} propagation so the epoch bump and
     * the subject's terminal write always commit together. A drill that called it bare would be
     * exercising a shape production refuses, so the helper opens the transaction the settler would
     * have opened for its terminal write.
     *
     * @param key the lease key
     * @param expectedEpoch the epoch the settler observed
     * @return the settler's token, or empty when the lease moved on before the takeover
     */
    Optional<AiRunLease> takeOverForSettlement(AiRunLeaseKey key, long expectedEpoch) {
        return transactions.execute(
                status -> leaseService.takeOverForSettlement(key, expectedEpoch));
    }

    /**
     * Ages a tombstone so the reap's retention predicate matches it.
     *
     * @param key the tombstoned lease key
     */
    void ageTombstone(AiRunLeaseKey key) {
        jdbcTemplate.update(
                "UPDATE ai_run_lease"
                        + " SET released_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 HOUR)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
    }

    void expire(AiRunLeaseKey key) {
        jdbcTemplate.update(
                "UPDATE ai_run_lease"
                        + " SET acquired_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE),"
                        + " heartbeat_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE),"
                        + " expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
    }

    Map<String, Object> leaseRow(AiRunLeaseKey key) {
        return jdbcTemplate.queryForMap(
                "SELECT owner, epoch, acquired_at, heartbeat_at, expires_at, released_at,"
                        + " TIMESTAMPDIFF(MICROSECOND, acquired_at, expires_at) AS ttl_micros,"
                        + " TIMESTAMPDIFF(SECOND, acquired_at, CURRENT_TIMESTAMP(6)) AS age_seconds"
                        + " FROM ai_run_lease"
                        + " WHERE workspace_id = ? AND subject_kind = ? AND subject_id = ?",
                key.workspaceId(), key.subject().wireKey(), key.subjectId());
    }

    int leaseCount() {
        return leaseCount(workspace.getId());
    }

    int leaseCount(int workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id = ?",
                Integer.class, workspaceId);
        return count == null ? 0 : count;
    }
}

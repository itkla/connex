package ooo.klae.connex.backend.ai.lease;

import java.util.Map;
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

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Shared real-database fixture for the run-lease drills: one throwaway organization, workspace, and
 * member, plus the transaction template the caller-joining lease methods require.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractAiRunLeaseIntegrationTest {

    static final String CHAT_TURN = "chat_turn";

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

    AiRunLease acquire(AiRunLeaseKey key) {
        return transactions.execute(status -> leaseService.acquireInCurrentTransaction(key));
    }

    boolean release(AiRunLeaseKey key) {
        return Boolean.TRUE.equals(
                transactions.execute(status -> leaseService.releaseHeldInCurrentTransaction(key)));
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
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run_lease WHERE workspace_id = ?",
                Integer.class, workspace.getId());
        return count == null ? 0 : count;
    }
}

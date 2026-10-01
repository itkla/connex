package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/**
 * Reproduces the self-wait behind #1879 against a real database and the real audit services.
 *
 * <p>A successful audit joins its caller and takes the workspace's integrity head {@code FOR UPDATE}
 * on the caller's transaction. A failure audit raised later in that same transaction used to append
 * in a {@code REQUIRES_NEW} transaction that needs the same head, so it suspended the holder, waited
 * on it for the full InnoDB lock-wait timeout, and was then swallowed and lost. Each case commits a
 * fresh workspace, because {@code audit_log} is append-only.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditFailureUnderHeldHeadIntegrationTest extends AbstractServiceTest {
    private static final long WELL_INSIDE_LOCK_WAIT_MS = 15_000;

    @Autowired private AuditService auditService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * The holder is often being rolled back by the very failure being audited, so the row must
     * survive a rollback as well as a commit.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureAuditUnderAHeldHeadIsRecordedWithoutWaitingOnItself(boolean rollBack) {
        Workspace fresh = committedWorkspace();
        TransactionTemplate holder = new TransactionTemplate(transactionManager);

        long started = System.nanoTime();
        holder.executeWithoutResult(status -> {
            auditService.record("note.create", "note", 1, "Probe", "Created note", null);
            auditService.recordFailure("activity.create", "activity", null, "Probe",
                    "Could not log activity", "DataIntegrityViolationException");
            if (rollBack) {
                status.setRollbackOnly();
            }
        });
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < WELL_INSIDE_LOCK_WAIT_MS,
                "the failure audit waited " + elapsedMs + " ms on its own transaction's integrity head");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log"
                        + " WHERE workspace_id = ? AND action = 'activity.create' AND outcome = 'failure'",
                Integer.class, fresh.getId()),
                "the failure audit must be recorded whether the holder committed or rolled back");
        assertEquals(rollBack ? 0 : 1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log"
                        + " WHERE workspace_id = ? AND action = 'note.create' AND outcome = 'success'",
                Integer.class, fresh.getId()),
                "the joined success audit commits or rolls back with its holder, as before");
    }

    private Workspace committedWorkspace() {
        Organization organization = new Organization();
        organization.setName("Held head " + unique());
        organization.setSlug("held-head-" + unique());
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), currentUser.getId());
        Workspace fresh = new Workspace();
        fresh.setOrgId(organization.getId());
        fresh.setName("Held head " + unique());
        fresh.setSlug("held-head-" + unique());
        workspaceMapper.insert(fresh);
        workspaceMapper.addMember(fresh.getId(), currentUser.getId(), "owner");
        workspace = fresh;
        authenticateAs(currentUser, fresh.getId());
        return fresh;
    }
}

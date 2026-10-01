package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.services.AbstractServiceTest;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.OrgMemberService;

/**
 * Pins who a deferred secret-use audit names (#1931). A secret read inside
 * {@link AutomationExecutor#runAs} joins the transaction that encloses the call, so its audit is
 * appended when that transaction completes, after {@code runAs} has already restored its caller's
 * security context. On the scheduler threads that drive workflow deliveries that context is empty, so
 * the row named nobody. It must name the automation principal that used the secret whatever context is
 * in place at completion, and leave that context in place afterwards.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SecretUseAuditActorIntegrationTest extends AbstractServiceTest {
    @Autowired private SecretStore secretStore;
    @Autowired private AutomationExecutor automationExecutor;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Integer freshWorkspaceId;
    private User automationPrincipal;

    @ParameterizedTest(name = "caller signed in: {0}")
    @ValueSource(booleans = {true, false})
    void aSecretUsedUnderRunAsIsAuditedAgainstTheAutomationPrincipal(boolean callerSignedIn) {
        committedWorkspace();
        automationPrincipal = newUser();
        String plaintext = "smtp-" + unique();
        String reference = secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, freshWorkspaceId, plaintext);
        if (!callerSignedIn) {
            SecurityContextHolder.clearContext();
        }
        SecurityContext callerContext = SecurityContextHolder.getContext();

        String used = new TransactionTemplate(transactionManager).execute(status ->
                automationExecutor.runAs(freshWorkspaceId, automationPrincipal, "member",
                        () -> secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, freshWorkspaceId,
                                reference)));

        assertEquals(plaintext, used);
        assertSame(callerContext, SecurityContextHolder.getContext());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_log
                WHERE action = 'secret_store.secret.use'
                  AND workspace_id = ?
                """, Integer.class, freshWorkspaceId));
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT actor_id, actor_label
                FROM audit_log
                WHERE action = 'secret_store.secret.use'
                  AND workspace_id = ?
                """, freshWorkspaceId);
        assertEquals(Integer.valueOf(automationPrincipal.getId()),
                audit.get("actor_id") instanceof Integer actorId ? actorId : null);
        assertEquals(automationPrincipal.getDisplayName(), audit.get("actor_label"));
    }

    @AfterEach
    void deleteCommittedSecret() {
        if (freshWorkspaceId != null) {
            jdbcTemplate.update("DELETE FROM secret_value WHERE scope_type = 'workspace' AND scope_id = ?",
                    freshWorkspaceId);
        }
    }

    /**
     * Deletes the committed users, including the one {@code AbstractServiceTest} made an owner of the
     * shared default workspace, which later classes would otherwise count as an eligible approver or
     * delegate. It is a separate method so a failing secret delete cannot skip it: JUnit runs every
     * after-each method even when one throws. Memberships cascade, audit rows keep their signed
     * {@code integrity_actor_id}, and no foreign key to {@code app_user} restricts the delete.
     */
    @AfterEach
    void deleteCommittedUsers() {
        if (automationPrincipal != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", automationPrincipal.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    private void committedWorkspace() {
        Organization organization = new Organization();
        organization.setName("Secret audit " + unique());
        organization.setSlug("secret-audit-" + unique());
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), currentUser.getId());
        Workspace fresh = new Workspace();
        fresh.setOrgId(organization.getId());
        fresh.setName("Secret audit " + unique());
        fresh.setSlug("secret-audit-" + unique());
        workspaceMapper.insert(fresh);
        workspaceMapper.addMember(fresh.getId(), currentUser.getId(), "owner");
        freshWorkspaceId = fresh.getId();
        workspace = fresh;
        authenticateAs(currentUser, fresh.getId());
    }
}

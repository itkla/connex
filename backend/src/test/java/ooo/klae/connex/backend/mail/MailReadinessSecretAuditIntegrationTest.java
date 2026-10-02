package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.services.AbstractServiceTest;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.OrgMemberService;

/**
 * Pins that asking whether a workspace can send mail never decrypts its SMTP password (#1932). The
 * readiness check used to resolve the full sender, so every workflow step and campaign enrolment wrote
 * an audited "Secret used" row — with no actor when it ran before {@code runAs}. Readiness and a send
 * still choose the sender the same way; only the send decrypts, which the final resolution proves is
 * visible to this test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailReadinessSecretAuditIntegrationTest extends AbstractServiceTest {
    private static final String DANGLING_REFERENCE = "secret:v1:2147483647";

    @Autowired private DeliveryProviderConfigService deliveryProviderConfigService;
    @Autowired private MailConfigResolver mailConfigResolver;
    @Autowired private SecretCipher secretCipher;
    @Autowired private MailConfigMapper mailConfigMapper;
    @Autowired private AutomationExecutor automationExecutor;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Integer freshWorkspaceId;
    private User automationPrincipal;

    @Test
    void readinessNeverDecryptsTheWorkspacePasswordButSendingStillDoes() {
        committedWorkspace();
        automationPrincipal = newUser();
        String reference = secretCipher.encryptForWorkspace(freshWorkspaceId, "smtp-" + unique());
        mailConfigMapper.upsert(override(reference));

        assertTrue(deliveryProviderConfigService.isReady(freshWorkspaceId, DeliveryChannel.EMAIL));
        Boolean readyUnderRunAs = new TransactionTemplate(transactionManager).execute(status ->
                automationExecutor.runAs(freshWorkspaceId, automationPrincipal, "member",
                        () -> deliveryProviderConfigService.isReady(freshWorkspaceId, DeliveryChannel.EMAIL)));
        assertEquals(Boolean.TRUE, readyUnderRunAs);
        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("workspace_override", true),
                mailConfigResolver.readinessForWorkspace(freshWorkspaceId));

        assertEquals(1, mailConfigMapper.updatePasswordReference(freshWorkspaceId, DANGLING_REFERENCE));
        assertFalse(deliveryProviderConfigService.isReady(freshWorkspaceId, DeliveryChannel.EMAIL));
        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("unconfigured", false),
                mailConfigResolver.readinessForWorkspace(freshWorkspaceId));
        assertEquals(0, secretAudits("secret_store.secret.use"));
        assertEquals(0, secretAudits("secret_store.secret.use_failed"));

        assertEquals(1, mailConfigMapper.updatePasswordReference(freshWorkspaceId, reference));
        ResolvedMailConfig resolved = mailConfigResolver.resolveForWorkspace(freshWorkspaceId);
        assertNotNull(resolved);
        assertEquals(1, secretAudits("secret_store.secret.use"));
    }

    @AfterEach
    void deleteCommittedMailConfig() {
        if (freshWorkspaceId != null) {
            jdbcTemplate.update("DELETE FROM workspace_mail_config WHERE workspace_id = ?", freshWorkspaceId);
            jdbcTemplate.update("DELETE FROM secret_value WHERE scope_type = 'workspace' AND scope_id = ?",
                    freshWorkspaceId);
        }
    }

    /**
     * Deletes the committed users, including the one {@code AbstractServiceTest} made an owner of the
     * shared default workspace, which later classes would otherwise count as an eligible approver or
     * delegate. It is a separate method so a failing delete above cannot skip it: JUnit runs every
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

    private int secretAudits(String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND workspace_id = ?",
                Integer.class, action, freshWorkspaceId);
        return count == null ? 0 : count;
    }

    private WorkspaceMailConfig override(String passwordReference) {
        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setWorkspaceId(freshWorkspaceId);
        config.setEnabled(true);
        config.setHost("smtp.readiness.test");
        config.setPort(587);
        config.setUsername("sender@readiness.test");
        config.setPasswordEnc(passwordReference);
        config.setFromAddress("sender@readiness.test");
        config.setStarttls(true);
        config.setAuth(true);
        return config;
    }

    private void committedWorkspace() {
        Organization organization = new Organization();
        organization.setName("Mail readiness " + unique());
        organization.setSlug("mail-readiness-" + unique());
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), currentUser.getId());
        Workspace fresh = new Workspace();
        fresh.setOrgId(organization.getId());
        fresh.setName("Mail readiness " + unique());
        fresh.setSlug("mail-readiness-" + unique());
        workspaceMapper.insert(fresh);
        workspaceMapper.addMember(fresh.getId(), currentUser.getId(), "owner");
        freshWorkspaceId = fresh.getId();
        workspace = fresh;
        authenticateAs(currentUser, fresh.getId());
    }
}

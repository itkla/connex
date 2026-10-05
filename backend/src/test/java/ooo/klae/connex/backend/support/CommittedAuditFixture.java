package ooo.klae.connex.backend.support;

import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Committed audit parents in a private organization. Append-only audits retain these roots;
 * memberships and business fixtures belong in the caller's rolled-back test transaction.
 */
public record CommittedAuditFixture(Workspace workspace, User actor) {

    /**
     * Commits only the three audit FK parents, without registration or membership side effects.
     * Create before starting the test transaction so its repeatable-read snapshot sees the roots.
     */
    public static CommittedAuditFixture create(
            PlatformTransactionManager fixtureTransactionManager,
            OrganizationMapper organizationMapper,
            WorkspaceMapper workspaceMapper,
            UserMapper userMapper) {
        TransactionTemplate template = new TransactionTemplate(fixtureTransactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Objects.requireNonNull(template.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Organization organization = new Organization();
            organization.setName("Audit fixture " + suffix);
            organization.setSlug("audit-fixture-" + suffix);
            organizationMapper.insert(organization);

            Workspace workspace = new Workspace();
            workspace.setOrgId(organization.getId());
            workspace.setName("Audit fixture " + suffix);
            workspace.setSlug("audit-fixture-" + suffix);
            workspaceMapper.insert(workspace);

            User actor = new User();
            actor.setUsername("audit_" + suffix);
            actor.setDisplayName("Audit fixture " + suffix);
            actor.setEmail(suffix + "@example.com");
            actor.setPasswordHash("hash_" + suffix);
            actor.setTimezone("UTC");
            userMapper.insert(actor);
            return new CommittedAuditFixture(workspace, actor);
        }), "committed audit fixture");
    }
}

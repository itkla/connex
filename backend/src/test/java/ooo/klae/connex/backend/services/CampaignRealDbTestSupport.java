package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

abstract class CampaignRealDbTestSupport extends AbstractServiceTest {

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        CampaignActorWorkspace fixture = newCampaignWorkspaceActor();
        workspace = fixture.workspace();
        currentUser = fixture.actor();
        authenticateAs(currentUser, workspace.getId());
    }

    @AfterEach
    protected void cleanCommittedCampaignFixtures() {
        clearAuthentication();
        for (int index = workspaceIds.size() - 1; index >= 0; index--) {
            int workspaceId = workspaceIds.get(index);
            jdbcTemplate.update("DELETE FROM connector_config WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_delivery_event WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_delivery WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_audience_export WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_send WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_message_revision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_message WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_audience_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_audience_snapshot WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign_audience WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                    "UPDATE campaign SET parent_campaign_id = NULL WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM campaign WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM contact_channel_consent_event WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM contact_channel_consent WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM suppression_entry WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update(
                    "DELETE wrp FROM workspace_role_permission wrp"
                            + " JOIN workspace_role wr ON wr.id = wrp.workspace_role_id"
                            + " WHERE wr.workspace_id = ?",
                    workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int index = userIds.size() - 1; index >= 0; index--) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userIds.get(index));
        }
        for (int index = organizationIds.size() - 1; index >= 0; index--) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationIds.get(index));
        }
        workspaceIds.clear();
        userIds.clear();
        organizationIds.clear();
    }

    protected CampaignActorWorkspace newCampaignWorkspaceActor() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Campaign tests " + suffix);
        organization.setSlug("campaign-tests-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());

        Workspace createdWorkspace = new Workspace();
        createdWorkspace.setOrgId(organization.getId());
        createdWorkspace.setName("Campaign tests " + suffix);
        createdWorkspace.setSlug("campaign-tests-" + suffix);
        workspaceMapper.insert(createdWorkspace);
        workspaceIds.add(createdWorkspace.getId());

        User actor = newCampaignAdmin(createdWorkspace);
        return new CampaignActorWorkspace(actor, createdWorkspace);
    }

    protected User newCampaignAdmin(Workspace targetWorkspace) {
        String suffix = unique();
        User actor = user("campaign_admin_" + suffix, "Campaign Admin " + suffix,
                "admin-" + suffix + "@campaign.test");
        workspaceMapper.addMember(targetWorkspace.getId(), actor.getId(), "admin");
        return actor;
    }

    protected CampaignActorRole newCampaignActor(Workspace targetWorkspace) {
        return newCampaignActor(targetWorkspace, List.of(
                Permission.CAMPAIGN_VIEW,
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE));
    }

    protected CampaignActorRole newCampaignActor(
            Workspace targetWorkspace, List<Permission> permissions) {
        String suffix = unique();
        User actor = user("campaign_actor_" + suffix, "Campaign Actor " + suffix,
                "actor-" + suffix + "@campaign.test");
        workspaceMapper.addMember(targetWorkspace.getId(), actor.getId(), "member");

        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(targetWorkspace.getId());
        role.setName("Campaign manager " + suffix);
        DirectAuthentication previous = captureDirectAuthentication();
        authenticateAs(actor, targetWorkspace.getId());
        try {
            roleMapper.insertRole(role);
            roleMapper.insertPermissions(targetWorkspace.getId(), role.getId(), permissions.stream()
                    .map(Permission::name)
                    .toList());
            workspaceMapper.setMemberCustomRole(targetWorkspace.getId(), actor.getId(), role.getId());
        } finally {
            restoreDirectAuthentication(previous);
        }
        return new CampaignActorRole(actor, role);
    }

    private DirectAuthentication captureDirectAuthentication() {
        TenantIdentity tenant = tenantContext.isResolved()
                ? new TenantIdentity(
                        tenantContext.getWorkspaceId(),
                        tenantContext.getOrgId(),
                        tenantContext.getUserId(),
                        tenantContext.getRole(),
                        tenantContext.getScopeCatalog())
                : null;
        return new DirectAuthentication(
                SecurityContextHolder.getContext().getAuthentication(),
                RequestContextHolder.getRequestAttributes(),
                tenant);
    }

    private void restoreDirectAuthentication(DirectAuthentication previous) {
        if (previous.authentication() == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(previous.authentication());
        }
        if (previous.requestAttributes() == null) {
            RequestContextHolder.resetRequestAttributes();
        } else {
            RequestContextHolder.setRequestAttributes(previous.requestAttributes());
        }
        TenantIdentity tenant = previous.tenant();
        if (tenant == null) {
            tenantContext.clear();
        } else {
            tenantContext.set(
                    tenant.workspaceId(),
                    tenant.orgId(),
                    tenant.userId(),
                    tenant.role(),
                    tenant.catalog());
        }
    }

    private User user(String username, String displayName, String email) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setPasswordHash("hash_" + unique());
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        userIds.add(user.getId());
        return user;
    }

    protected record CampaignActorWorkspace(User actor, Workspace workspace) {
    }

    protected record CampaignActorRole(User actor, WorkspaceRole role) {
    }

    private record DirectAuthentication(
            Authentication authentication,
            RequestAttributes requestAttributes,
            TenantIdentity tenant) {
    }

    private record TenantIdentity(
            int workspaceId,
            int orgId,
            int userId,
            String role,
            String catalog) {
    }
}

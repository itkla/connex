package ooo.klae.connex.backend.services;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractCommittedDocumentDeliveryServiceTest
        extends AbstractDocumentDeliveryServiceTest {
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;

    private Organization organization;

    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        String suffix = unique();
        organization = new Organization();
        organization.setName("Committed document delivery " + suffix);
        organization.setSlug("committed-document-delivery-" + suffix);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Committed document delivery " + suffix);
        workspace.setSlug("committed-document-delivery-" + suffix);
        workspaceMapper.insert(workspace);

        currentUser = new User();
        currentUser.setUsername("committed_document_delivery_" + suffix);
        currentUser.setDisplayName("Committed document delivery " + suffix);
        currentUser.setEmail("committed-document-delivery-" + suffix + "@example.com");
        currentUser.setPasswordHash("hash-" + suffix);
        currentUser.setTimezone("UTC");
        userMapper.insert(currentUser);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "member");

        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Committed document delivery actor " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(
            Permission.DEAL_CREATE.name(),
            Permission.DEAL_UPDATE.name(),
            Permission.DEAL_DELETE.name(),
            Permission.DOCUMENT_MANAGE.name(),
            Permission.DOCUMENT_SEND.name(),
            Permission.DOCUMENT_APPROVE.name()));
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), role.getId());
        authenticateAs(currentUser, workspace.getId());
    }

    @AfterEach
    void cleanCommittedDocumentDeliveryFixtures() {
        if (organization != null) {
            List<TableLifecycle> declarations = TenantLifecycleRegistry.declarations().values()
                .stream()
                .filter(TableLifecycle::direct)
                .sorted(Comparator.comparingInt(TableLifecycle::deleteOrder))
                .toList();
            List<Integer> workspaceIds = jdbcTemplate.queryForList(
                "SELECT id FROM workspace WHERE org_id = ?",
                Integer.class,
                organization.getId());
            for (int workspaceId : workspaceIds) {
                for (TableLifecycle declaration : declarations) {
                    for (var preparation : declaration.preparations()) {
                        tenantTeardownTransaction.prepare(
                            workspaceId, declaration, (NullifyReference) preparation);
                    }
                    while (tenantTeardownTransaction.deleteBatch(
                            workspaceId, declaration, 100) > 0) {
                    }
                }
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
            }
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }
}

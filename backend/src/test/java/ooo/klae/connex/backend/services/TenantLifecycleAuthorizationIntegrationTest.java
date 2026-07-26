package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantLifecycleAuthorizationIntegrationTest extends AbstractServiceTest {
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private TenantExportService exportService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> additionalUserIds = new ArrayList<>();

    @AfterEach
    void cleanCommittedAuthorizationRoots() {
        for (int workspaceId : workspaceIds.reversed()) {
            jdbcTemplate.update(
                "DELETE FROM tenant_operation_lease WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (int organizationId : organizationIds.reversed()) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (int userId : additionalUserIds.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    @Test
    void exportRejectsMembersAndAdminsOfAnotherOrganizationWithoutAnOracle() {
        Organization pathOrganization = createOrganization(currentUser);
        jdbcTemplate.update(
            "UPDATE org_member SET org_role = 'admin'"
                + " WHERE org_id = ? AND user_id = ?",
            pathOrganization.getId(),
            currentUser.getId());
        User foreignOwner = newUser();
        additionalUserIds.add(foreignOwner.getId());
        Organization foreignOrganization = createOrganization(foreignOwner);
        Workspace foreignWorkspace = createWorkspace(foreignOrganization, foreignOwner);
        User member = newUser();
        additionalUserIds.add(member.getId());

        assertThrows(
            ForbiddenException.class,
            () -> exportService.prepare(
                pathOrganization.getId(),
                foreignWorkspace.getId(),
                member.getId()));
        assertThrows(
            ForbiddenException.class,
            () -> exportService.prepare(
                foreignOrganization.getId(),
                foreignWorkspace.getId(),
                currentUser.getId()));
        assertThrows(
            ResourceNotFoundException.class,
            () -> exportService.prepare(
                pathOrganization.getId(),
                foreignWorkspace.getId(),
                currentUser.getId()));
    }

    @Test
    void teardownRequiresOwnerAndExactConfirmation() {
        Organization adminOrganization = createOrganization(currentUser);
        Workspace adminWorkspace = createWorkspace(adminOrganization, currentUser);
        jdbcTemplate.update(
            "UPDATE org_member SET org_role = 'admin'"
                + " WHERE org_id = ? AND user_id = ?",
            adminOrganization.getId(),
            currentUser.getId());

        assertThrows(
            ForbiddenException.class,
            () -> teardownService.teardownWorkspace(
                adminOrganization.getId(),
                adminWorkspace.getId(),
                currentUser.getId(),
                adminWorkspace.getSlug()));
        assertThrows(
            ForbiddenException.class,
            () -> teardownService.teardownOrganization(
                adminOrganization.getId(),
                currentUser.getId(),
                adminOrganization.getSlug()));

        Organization ownerOrganization = createOrganization(currentUser);
        Workspace ownerWorkspace = createWorkspace(ownerOrganization, currentUser);

        assertThrows(
            BadRequestException.class,
            () -> teardownService.teardownWorkspace(
                ownerOrganization.getId(),
                ownerWorkspace.getId(),
                currentUser.getId(),
                "wrong"));
        assertThrows(
            BadRequestException.class,
            () -> teardownService.teardownOrganization(
                ownerOrganization.getId(),
                currentUser.getId(),
                "wrong"));
    }

    private Organization createOrganization(User owner) {
        Organization organization = new Organization();
        organization.setName("Lifecycle authorization " + unique());
        organization.setSlug("lifecycle-authorization-" + unique());
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        orgMemberService.addFoundingOwner(organization.getId(), owner.getId());
        return organization;
    }

    private Workspace createWorkspace(Organization organization, User owner) {
        Workspace created = new Workspace();
        created.setOrgId(organization.getId());
        created.setName("Lifecycle authorization " + unique());
        created.setSlug("lifecycle-authorization-" + unique());
        workspaceMapper.insert(created);
        workspaceMapper.addMember(created.getId(), owner.getId(), "owner");
        workspaceIds.add(created.getId());
        return created;
    }
}

package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

class ShareServiceTest extends AbstractServiceTest {

    @Autowired ShareService shareService;
    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;
    @Autowired OrganizationMapper organizationMapper;

    @AfterEach
    void clearContext() {
        clearRequestContext();
    }

    private Company companyIn(int workspaceId) {
        Company company = new Company();
        company.setName("Acme " + unique());
        company.setWorkspaceId(workspaceId);
        companyMapper.insert(company);
        return company;
    }

    /**
     * Creates a second workspace inside {@code first}'s organization the way a real
     * owner does: from an administrative tenant context (the placement rule only
     * reuses the active org for owner/admin creators).
     */
    private WorkspaceMembershipDto createSiblingWorkspace(WorkspaceMembershipDto first, String name) {
        tenantContext.set(first.getId(), workspaceService.getOrgId(first.getId()), currentUser.getId(), "owner", null);
        WorkspaceMembershipDto sibling = workspaceService.createWorkspace(name, currentUser.getId());
        authenticateAs(currentUser, first.getId());
        return sibling;
    }

    @Test
    void sharingMakesACompanyVisibleToTheGrantee() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner WS", currentUser.getId());
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee WS");
        Company company = companyIn(a.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
        assertFalse(companyMapper.exists(b.getId(), company.getId()));

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        authenticateAs(currentUser, b.getId());

        Company seenByB = companyMapper.getCompanyById(b.getId(), company.getId());
        assertNotNull(seenByB);
        assertTrue(companyMapper.exists(b.getId(), company.getId()));
        assertEquals(a.getId(), seenByB.getWorkspaceId());
    }

    @Test
    void unshareRemovesVisibility() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner2 WS", currentUser.getId());
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee2 WS");
        Company company = companyIn(a.getId());

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        assertNotNull(companyMapper.getCompanyById(b.getId(), company.getId()));

        shareService.unshare("company", company.getId(), b.getId());
        authenticateAs(currentUser, b.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
    }

    @Test
    void cannotShareToAWorkspaceYouDoNotBelongTo() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner3 WS", currentUser.getId());
        Company company = companyIn(a.getId());

        Workspace foreign = new Workspace();
        foreign.setName("Foreign WS");
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        User outsider = newUser();
        workspaceMapper.addMember(foreign.getId(), outsider.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), foreign.getId(), false));
    }

    @Test
    void cannotShareAcrossOrganizations() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Org1 WS", currentUser.getId());
        Company company = companyIn(a.getId());

        Organization otherOrg = new Organization();
        otherOrg.setName("Other Org");
        otherOrg.setSlug("other-org-" + unique());
        organizationMapper.insert(otherOrg);
        Workspace otherOrgWs = new Workspace();
        otherOrgWs.setOrgId(otherOrg.getId());
        otherOrgWs.setName("Other Org WS");
        otherOrgWs.setSlug("other-org-ws-" + unique());
        workspaceMapper.insert(otherOrgWs);
        workspaceMapper.addMember(otherOrgWs.getId(), currentUser.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), otherOrgWs.getId(), false));
    }
}

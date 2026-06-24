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
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.TenantContext;

class ShareServiceTest extends AbstractServiceTest {

    @Autowired ShareService shareService;
    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    private Company companyIn(int workspaceId) {
        Company company = new Company();
        company.setName("Acme " + unique());
        company.setWorkspaceId(workspaceId);
        companyMapper.insert(company);
        return company;
    }

    @Test
    void sharingMakesACompanyVisibleToTheGrantee() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner WS", currentUser.getId());
        WorkspaceMembershipDto b = workspaceService.createWorkspace("Grantee WS", currentUser.getId());
        Company company = companyIn(a.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
        assertFalse(companyMapper.exists(b.getId(), company.getId()));

        tenantContext.set(a.getId(), currentUser.getId(), "owner");
        shareService.share("company", company.getId(), b.getId(), false);
        tenantContext.clear();

        Company seenByB = companyMapper.getCompanyById(b.getId(), company.getId());
        assertNotNull(seenByB);
        assertTrue(companyMapper.exists(b.getId(), company.getId()));
        assertEquals(a.getId(), seenByB.getWorkspaceId());
    }

    @Test
    void unshareRemovesVisibility() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner2 WS", currentUser.getId());
        WorkspaceMembershipDto b = workspaceService.createWorkspace("Grantee2 WS", currentUser.getId());
        Company company = companyIn(a.getId());

        tenantContext.set(a.getId(), currentUser.getId(), "owner");
        shareService.share("company", company.getId(), b.getId(), false);
        assertNotNull(companyMapper.getCompanyById(b.getId(), company.getId()));

        shareService.unshare("company", company.getId(), b.getId());
        tenantContext.clear();

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

        tenantContext.set(a.getId(), currentUser.getId(), "owner");
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), foreign.getId(), false));
    }
}

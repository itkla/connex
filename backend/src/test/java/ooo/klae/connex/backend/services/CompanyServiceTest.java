package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

class CompanyServiceTest extends AbstractServiceTest {

    @Autowired CompanyService companyService;
    @Autowired ShareMapper shareMapper;
    @Autowired NoteService noteService;
    @Autowired ReferenceService referenceService;
    @Autowired TenantContext tenantContext;

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void deleteCompany_rejectsSharedInCompany() {
        Workspace other = newWorkspaceInCurrentOrg();
        Company foreign = companyInWorkspace(other);
        shareMapper.shareCompany(foreign.getId(), other.getId(), workspace.getId(), currentUser.getId(), true);

        assertTrue(companyMapper.exists(workspace.getId(), foreign.getId()));
        assertFalse(companyMapper.existsOwned(workspace.getId(), foreign.getId()));
        assertThrows(ResourceNotFoundException.class, () -> companyService.deleteCompany(foreign.getId()));
        assertTrue(companyMapper.existsOwned(other.getId(), foreign.getId()));
    }

    @Test
    void deleteCompany_removesReferencesFromWorkspacesWhereCompanyWasShared() {
        Workspace owner = newWorkspaceInCurrentOrg();
        Company foreign = companyInWorkspace(owner);
        shareMapper.shareCompany(foreign.getId(), owner.getId(), workspace.getId(), currentUser.getId(), true);
        Note note = new Note();
        note.setContent("See [" + foreign.getName() + "](company:" + foreign.getId() + ")");
        note.setVisibility("workspace");
        Note created = noteService.create(note);
        assertTrue(referenceService.referencesFor(workspace.getId(), ReferenceService.SOURCE_NOTE, created.getId()).stream()
            .anyMatch(reference -> ReferenceService.TYPE_COMPANY.equals(reference.getRefType())
                && reference.getRefId() == foreign.getId()));

        workspaceMapper.addMember(owner.getId(), currentUser.getId(), "owner");
        tenantContext.set(owner.getId(), workspaceMapper.getOrgId(owner.getId()), currentUser.getId(), "owner");
        companyService.deleteCompany(foreign.getId());

        assertTrue(referenceService.referencesFor(workspace.getId(), ReferenceService.SOURCE_NOTE, created.getId()).isEmpty());
    }

    @Test
    void getPersonsByCompanyId_returnsOnlyMatchingPeople() {
        Company company1 = newCompany();
        Company company2 = newCompany();
        Person p1 = newPerson(company1);
        Person p2 = newPerson(company2);

        List<Person> people = companyService.getPersonsByCompanyId(company1.getId());

        assertTrue(people.stream().anyMatch(x -> x.getId() == p1.getId()));
        assertTrue(people.stream().noneMatch(x -> x.getId() == p2.getId()));
    }

    @Test
    void getPersonsByCompanyId_throwsWhenCompanyMissing() {
        assertThrows(ResourceNotFoundException.class, () -> companyService.getPersonsByCompanyId(-1));
    }

    @Test
    void getDealsByCompanyId_returnsOnlyMatchingDeals() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company1 = newCompany();
        Company company2 = newCompany();
        Deal d1 = newDeal(pipeline, stage, company1);
        Deal d2 = newDeal(pipeline, stage, company2);

        List<Deal> deals = companyService.getDealsByCompanyId(company1.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == d1.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == d2.getId()));
    }

    @Test
    void getDealsByCompanyId_throwsWhenCompanyMissing() {
        assertThrows(ResourceNotFoundException.class, () -> companyService.getDealsByCompanyId(-1));
    }

    private Workspace newWorkspaceInCurrentOrg() {
        String s = unique();
        Workspace other = new Workspace();
        other.setName("Other Workspace " + s);
        other.setSlug("other-" + s);
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        assertTrue(orgId != null);
        other.setOrgId(orgId);
        workspaceMapper.insert(other);
        return other;
    }

    private Company companyInWorkspace(Workspace target) {
        String s = unique();
        Company company = new Company();
        company.setName("Foreign Company " + s);
        company.setWebsite("https://" + s + ".foreign.example.com");
        company.setIndustry("Tech");
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }
}

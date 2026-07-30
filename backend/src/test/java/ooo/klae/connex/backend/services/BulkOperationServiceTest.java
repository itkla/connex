package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;

class BulkOperationServiceTest extends AbstractServiceTest {

    @Autowired BulkOperationService bulkOperationService;
    @Autowired ShareMapper shareMapper;

    @Test
    void addTagToPersons_tagsEveryRecordAndReportsSuccess() {
        Tag tag = newTag();
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());

        BulkOperationResult result = bulkOperationService.addTagToPersons(List.of(p1.getId(), p2.getId()), tag.getId());

        assertEquals(2, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(hasTag(p1.getId(), tag.getId()));
        assertTrue(hasTag(p2.getId(), tag.getId()));
    }

    @Test
    void addTagToPersons_failsFastWhenTagMissing() {
        Person person = newPerson(newCompany());
        assertThrows(ResourceNotFoundException.class,
            () -> bulkOperationService.addTagToPersons(List.of(person.getId()), 999_999));
    }

    @Test
    void archivePersons_reportsPartialFailureForUnknownIdAndStillArchivesTheRest() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());

        BulkOperationResult result = bulkOperationService.archivePersons(List.of(p1.getId(), p2.getId(), 999_999));

        assertEquals(2, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getErrors().size());
        assertEquals(2, result.getErrors().get(0).getRowIndex());
        assertFalse(personMapper.exists(workspace.getId(), p1.getId()));
        assertFalse(personMapper.exists(workspace.getId(), p2.getId()));
    }

    @Test
    void archivePersons_skipsForeignWorkspaceRecordsAndNeverMutatesThem() {
        Person local = newPerson(newCompany());
        Workspace other = newOtherWorkspace();
        Person foreign = personInWorkspace(other);

        BulkOperationResult result = bulkOperationService.archivePersons(List.of(local.getId(), foreign.getId()));

        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertFalse(personMapper.exists(workspace.getId(), local.getId()));
        assertNotNull(personMapper.getPersonById(other.getId(), foreign.getId()),
            "a record in another workspace must never be touched by a bulk operation");
    }

    @Test
    void archivePersons_skipsRecordsMerelySharedIntoTheWorkspace() {
        Workspace other = newOtherWorkspace();
        Person foreign = personInWorkspace(other);
        shareMapper.sharePerson(foreign.getId(), other.getId(), workspace.getId(), currentUser.getId(), true);

        assertTrue(personMapper.exists(workspace.getId(), foreign.getId()),
            "a shared-in record is read-visible");
        assertFalse(personMapper.existsOwned(workspace.getId(), foreign.getId()),
            "but it is not owned by the workspace");

        BulkOperationResult result = bulkOperationService.archivePersons(List.of(foreign.getId()));

        assertEquals(0, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertNotNull(personMapper.getPersonById(other.getId(), foreign.getId()),
            "a record only shared into the workspace must never be mutated by a bulk operation");
    }

    @Test
    void archivePersons_deduplicatesRepeatedIds() {
        Person person = newPerson(newCompany());

        BulkOperationResult result = bulkOperationService.archivePersons(List.of(person.getId(), person.getId()));

        assertEquals(1, result.getSucceeded());
        assertEquals(0, result.getFailed());
    }

    @Test
    void addTagToCompanies_tagsEveryCompany() {
        Tag tag = newTag();
        Company c1 = newCompany();
        Company c2 = newCompany();

        BulkOperationResult result = bulkOperationService.addTagToCompanies(List.of(c1.getId(), c2.getId()), tag.getId());

        assertEquals(2, result.getSucceeded());
        assertTrue(tagMapper.getTagsByCompanyId(workspace.getId(), c1.getId()).stream()
            .anyMatch(t -> t.getId() == tag.getId()));
    }

    @Test
    void assignOwnerToDeals_assignsOwnerToEveryDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User newOwner = newUser();

        BulkOperationResult result = bulkOperationService.assignOwnerToDeals(List.of(d1.getId(), d2.getId()), newOwner.getId());

        assertEquals(2, result.getSucceeded());
        assertEquals(newOwner.getId(), dealMapper.getDealById(workspace.getId(), d1.getId()).getOwnerId());
    }

    @Test
    void assignOwnerToDeals_failsFastWhenOwnerNotAMember() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        assertThrows(ForbiddenException.class,
            () -> bulkOperationService.assignOwnerToDeals(List.of(deal.getId()), 999_999));
    }

    @Test
    void assignOwnerToCompaniesUpdatesOwnedRowsAndReportsForeignAndStaleIds() {
        Company local = newCompany();
        Workspace other = newOtherWorkspace();
        Company foreign = companyInWorkspace(other);
        User owner = newUser();

        BulkOperationResult result = bulkOperationService.assignOwnerToCompanies(
            List.of(local.getId(), foreign.getId(), 999_999), owner.getId());

        assertEquals(1, result.getSucceeded());
        assertEquals(2, result.getFailed());
        assertEquals(owner.getId(),
            companyMapper.getCompanyById(workspace.getId(), local.getId()).getOwnerId());
        assertNull(companyMapper.getCompanyById(other.getId(), foreign.getId()).getOwnerId());
    }

    @Test
    void assignOwnerToPersonsUpdatesOwnedRowsAndReportsForeignAndStaleIds() {
        Person local = newPerson(newCompany());
        Workspace other = newOtherWorkspace();
        Person foreign = personInWorkspace(other);
        User owner = newUser();

        BulkOperationResult result = bulkOperationService.assignOwnerToPersons(
            List.of(local.getId(), foreign.getId(), 999_999), owner.getId());

        assertEquals(1, result.getSucceeded());
        assertEquals(2, result.getFailed());
        assertEquals(owner.getId(),
            personMapper.getPersonById(workspace.getId(), local.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(other.getId(), foreign.getId()).getOwnerId());
    }

    @Test
    void recordOwnerBulkAssignmentsRejectNonMembersBeforeChangingAnyRows() {
        Company company = newCompany();
        Person person = newPerson(company);
        User outsider = newUser();
        workspaceMapper.removeMember(workspace.getId(), outsider.getId());

        assertThrows(ForbiddenException.class,
            () -> bulkOperationService.assignOwnerToCompanies(List.of(company.getId()), outsider.getId()));
        assertThrows(ForbiddenException.class,
            () -> bulkOperationService.assignOwnerToPersons(List.of(person.getId()), outsider.getId()));
        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
    }

    @Test
    void changeStageForDeals_movesEveryDealWithinItsPipeline() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, from, company);
        Deal d2 = newDeal(pipeline, from, company);

        BulkOperationResult result = bulkOperationService.changeStageForDeals(List.of(d1.getId(), d2.getId()), to.getId());

        assertEquals(2, result.getSucceeded());
        assertEquals(to.getId(), dealMapper.getDealById(workspace.getId(), d1.getId()).getStageId());
        assertEquals(to.getId(), dealMapper.getDealById(workspace.getId(), d2.getId()).getStageId());
    }

    @Test
    void changeStageForDeals_skipsDealsOutsideTheTargetStagesPipeline() {
        Pipeline pipelineA = newPipeline();
        Stage a0 = newStage(pipelineA, 0);
        Stage a1 = newStage(pipelineA, 1);
        Pipeline pipelineB = newPipeline();
        Stage b0 = newStage(pipelineB, 0);
        Company company = newCompany();
        Deal inA = newDeal(pipelineA, a0, company);
        Deal inB = newDeal(pipelineB, b0, company);

        BulkOperationResult result = bulkOperationService.changeStageForDeals(List.of(inA.getId(), inB.getId()), a1.getId());

        assertEquals(1, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(a1.getId(), dealMapper.getDealById(workspace.getId(), inA.getId()).getStageId());
        assertEquals(b0.getId(), dealMapper.getDealById(workspace.getId(), inB.getId()).getStageId());
    }

    @Test
    void changeStageForDeals_failsFastWhenStageMissing() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        assertThrows(ResourceNotFoundException.class,
            () -> bulkOperationService.changeStageForDeals(List.of(deal.getId()), 999_999));
    }

    private boolean hasTag(int personId, int tagId) {
        return tagMapper.getTagsByPersonId(workspace.getId(), personId).stream().anyMatch(t -> t.getId() == tagId);
    }

    private Workspace newOtherWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Person personInWorkspace(Workspace target) {
        String s = unique();
        Person person = new Person();
        person.setName("Foreign " + s);
        person.setEmail(s + ".foreign@example.com");
        person.setTitle("Engineer");
        person.setWorkspaceId(target.getId());
        personMapper.insert(person);
        return person;
    }

    private Company companyInWorkspace(Workspace target) {
        Company company = new Company();
        company.setName("Foreign " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }
}

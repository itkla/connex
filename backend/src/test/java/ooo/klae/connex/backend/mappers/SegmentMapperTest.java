package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class SegmentMapperTest extends AbstractMapperTest {
    @Autowired private SegmentMapper segmentMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ShareMapper shareMapper;

    @Test
    void ownerFieldConditionMatchesCurrentOwnerForCompanyAndPerson() {
        User owner = newUser();
        User other = newUser();
        Company mine = newCompany();
        Company theirs = newCompany();
        companyMapper.updateOwner(workspace.getId(), mine.getId(), owner.getId());
        companyMapper.updateOwner(workspace.getId(), theirs.getId(), other.getId());
        Person myPerson = newPerson(mine);
        Person theirPerson = newPerson(theirs);
        personMapper.updateOwner(workspace.getId(), myPerson.getId(), owner.getId());
        personMapper.updateOwner(workspace.getId(), theirPerson.getId(), other.getId());

        List<Integer> companyMatches = segmentMapper.companyIdsMatching(Map.of(
            "workspaceId", workspace.getId(), "field", "owner", "op", "is", "id", owner.getId()));
        assertTrue(companyMatches.contains(mine.getId()));
        assertFalse(companyMatches.contains(theirs.getId()));

        List<Integer> personMatches = segmentMapper.personIdsMatching(Map.of(
            "workspaceId", workspace.getId(), "field", "owner", "op", "is", "id", owner.getId()));
        assertTrue(personMatches.contains(myPerson.getId()));
        assertFalse(personMatches.contains(theirPerson.getId()));
    }

    @Test
    void suspendedPeopleAreExcludedFromUniversesMatchesAndPersonActivityPredicate() {
        Company company = newCompany();
        Person processable = newPerson(company);
        Person suspended = newPerson(company);
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Suspended activity");
        activity.setPerson(suspended);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2999-01-01 00:00:00");
        activityMapper.insert(activity);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);

        assertTrue(segmentMapper.personIdsInWorkspace(workspace.getId()).contains(processable.getId()));
        assertFalse(segmentMapper.personIdsInWorkspace(workspace.getId()).contains(suspended.getId()));
        assertFalse(segmentMapper.personIdsMatching(Map.of(
            "workspaceId", workspace.getId(),
            "field", "name",
            "op", "equals",
            "value", suspended.getName())).contains(suspended.getId()));
        assertTrue(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(processable.getId(), suspended.getId()))
            .contains(company.getId()));
        assertTrue(segmentMapper.companyIdsNoActivitySince(workspace.getId(), 365_000)
            .contains(company.getId()));
        assertTrue(segmentMapper.personLabels(
            workspace.getId(), List.of(suspended.getId())).stream()
            .anyMatch(label -> label.getId() == suspended.getId()));
    }

    @Test
    void sharedInProcessablePersonActivityStillCountsForOwnedDealCompany() {
        Company company = newCompany();
        Person target = newPerson(company);
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Workspace sibling = new Workspace();
        sibling.setName("Workspace " + unique());
        sibling.setSlug("workspace-" + unique());
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        assertNotNull(orgId);
        sibling.setOrgId(orgId);
        workspaceMapper.insert(sibling);
        Company siblingCompany = new Company();
        siblingCompany.setName("Company " + unique());
        siblingCompany.setWorkspaceId(sibling.getId());
        companyMapper.insert(siblingCompany);
        Person shared = new Person();
        shared.setName("Person " + unique());
        shared.setWorkspaceId(sibling.getId());
        shared.setCompany(siblingCompany);
        personMapper.insert(shared);
        assertTrue(shareMapper.sharePerson(
            shared.getId(), sibling.getId(), workspace.getId(), actor.getId(), false) > 0);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Shared contact activity");
        activity.setPerson(shared);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2999-01-01 00:00:00");
        activityMapper.insert(activity);
        assertFalse(segmentMapper.companyIdsNoActivitySince(
            workspace.getId(), 365_000).contains(company.getId()));
        assertFalse(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(target.getId()))
            .contains(company.getId()));

        assertTrue(shareMapper.unsharePerson(shared.getId(), sibling.getId(), workspace.getId()) > 0);
        assertTrue(segmentMapper.companyIdsNoActivitySince(workspace.getId(), 365_000)
            .contains(company.getId()));
        assertTrue(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(target.getId()))
            .contains(company.getId()));
    }
}

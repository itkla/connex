package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStakeholder;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;

class SharedPersonProjectionMapperTest extends AbstractMapperTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private TaskMapper taskMapper;

    @Test
    void sameOrgShareLifecycleGatesDealPersonRiskReminderAndNudgeProjections() {
        User recipient = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        dealMapper.updateOwner(workspace.getId(), deal.getId(), recipient.getId());
        Person owned = personIn(workspace, newCompany(), "Zulu Owned Control");
        dealMapper.addPerson(workspace.getId(), deal.getId(), owned.getId(), "owner");

        Workspace sibling = workspaceInOrg(orgId(workspace));
        Company foreignCompany = companyIn(sibling, "Sibling Company");
        Person shared = personIn(sibling, foreignCompany, "Alpha Shared Contact");
        assertEquals(1, shareMapper.sharePerson(
            shared.getId(), sibling.getId(), workspace.getId(), recipient.getId(), false));
        assertEquals(1, dealMapper.addPerson(
            workspace.getId(), deal.getId(), shared.getId(), "champion"));
        Task task = taskFor(recipient, shared, deal);

        assertEquals(Set.of(owned.getId(), shared.getId()), dealPersonIds(deal));
        assertEquals(Set.of(owned.getId(), shared.getId()), stakeholderIds(deal));
        assertEquals(Set.of(owned.getId(), shared.getId()), personIdsForDeal(deal));
        assertTrue(dealMapper.getDealsByPersonId(workspace.getId(), shared.getId()).stream()
            .anyMatch(candidate -> candidate.getId() == deal.getId()));
        assertTrue(personMapper.getEngagedPersonIds(workspace.getId()).contains(shared.getId()));
        assertEquals(shared.getId(), primaryContact(deal).personId());
        assertNull(sharedDealPerson(deal, shared).getPerson().getCompany());
        assertNull(sharedPersonForDeal(deal, shared).getCompany());
        assertEquals(shared.getId(), taskCandidate(task).getPersonId());
        assertTrue(nudgeCandidates(deal).stream()
            .anyMatch(candidate -> candidate.getPersonId() == shared.getId()));

        assertEquals(1, shareMapper.shareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId(), recipient.getId(), false));
        assertEquals(foreignCompany.getId(),
            sharedDealPerson(deal, shared).getPerson().getCompany().getId());
        assertEquals(foreignCompany.getId(), sharedPersonForDeal(deal, shared).getCompany().getId());

        assertEquals(1, shareMapper.unshareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId()));
        assertNull(sharedDealPerson(deal, shared).getPerson().getCompany());
        assertNull(sharedPersonForDeal(deal, shared).getCompany());

        assertEquals(1, shareMapper.unsharePerson(shared.getId(), sibling.getId(), workspace.getId()));
        assertEquals(Set.of(owned.getId()), dealPersonIds(deal));
        assertEquals(Set.of(owned.getId()), stakeholderIds(deal));
        assertEquals(Set.of(owned.getId()), personIdsForDeal(deal));
        assertTrue(dealMapper.getDealsByPersonId(workspace.getId(), shared.getId()).isEmpty());
        assertFalse(personMapper.getEngagedPersonIds(workspace.getId()).contains(shared.getId()));
        assertEquals(owned.getId(), primaryContact(deal).personId());
        assertNull(taskCandidate(task).getPersonId());
        assertNull(taskCandidate(task).getPersonLabel());
        assertTrue(nudgeCandidates(deal).stream()
            .noneMatch(candidate -> candidate.getPersonId() == shared.getId()));

        assertEquals(1, dealMapper.removePerson(workspace.getId(), deal.getId(), shared.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_person WHERE deal_id = ? AND person_id = ?",
            Integer.class, deal.getId(), shared.getId()));
    }

    @Test
    void forgedCrossOrgShareNeverEntersDealPersonRiskReminderOrNudgeProjections() {
        User recipient = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        dealMapper.updateOwner(workspace.getId(), deal.getId(), recipient.getId());
        Person owned = personIn(workspace, newCompany(), "Owned Control");
        dealMapper.addPerson(workspace.getId(), deal.getId(), owned.getId(), "owner");

        Workspace foreign = workspaceInOrg(newOrganization().getId());
        Person forged = personIn(foreign, companyIn(foreign, "Foreign Company"), "Forged Contact");
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            forged.getId(), workspace.getId(), recipient.getId(), false);
        jdbcTemplate.update(
            "INSERT INTO deal_person (deal_id, person_id, role) VALUES (?, ?, ?)",
            deal.getId(), forged.getId(), "champion");
        Task task = taskFor(recipient, forged, deal);

        assertEquals(Set.of(owned.getId()), dealPersonIds(deal));
        assertEquals(Set.of(owned.getId()), stakeholderIds(deal));
        assertEquals(Set.of(owned.getId()), personIdsForDeal(deal));
        assertEquals(owned.getId(), primaryContact(deal).personId());
        assertTrue(dealMapper.getDealsByPersonId(workspace.getId(), forged.getId()).isEmpty());
        assertFalse(personMapper.getEngagedPersonIds(workspace.getId()).contains(forged.getId()));
        assertNull(taskCandidate(task).getPersonId());
        assertNull(taskCandidate(task).getPersonLabel());
        assertTrue(nudgeCandidates(deal).stream()
            .noneMatch(candidate -> candidate.getPersonId() == forged.getId()));
    }

    private Set<Integer> dealPersonIds(Deal deal) {
        return dealMapper.getDealPeopleByDealId(workspace.getId(), deal.getId()).stream()
            .map(DealPerson::getPerson)
            .map(Person::getId)
            .collect(Collectors.toSet());
    }

    private Set<Integer> stakeholderIds(Deal deal) {
        Set<Integer> expected = dealMapper.getDealStakeholdersByDealId(
            workspace.getId(), deal.getId()).stream()
            .map(DealStakeholder::getPersonId)
            .collect(Collectors.toSet());
        assertEquals(expected, dealMapper.getDealStakeholdersByDealIds(
            workspace.getId(), List.of(deal.getId())).stream()
            .map(DealStakeholder::getPersonId)
            .collect(Collectors.toSet()));
        assertEquals(expected, dealMapper.getAllDealStakeholders(workspace.getId()).stream()
            .filter(stakeholder -> stakeholder.getDealId() == deal.getId())
            .map(DealStakeholder::getPersonId)
            .collect(Collectors.toSet()));
        return expected;
    }

    private Set<Integer> personIdsForDeal(Deal deal) {
        return personMapper.getPersonsByDealId(workspace.getId(), deal.getId()).stream()
            .map(Person::getId)
            .collect(Collectors.toSet());
    }

    private DealPrimaryContactDto primaryContact(Deal deal) {
        return dealMapper.getPrimaryContactsByDealIds(
            workspace.getId(), List.of(deal.getId())).getFirst();
    }

    private DealPerson sharedDealPerson(Deal deal, Person person) {
        return dealMapper.getDealPeopleByDealId(workspace.getId(), deal.getId()).stream()
            .filter(candidate -> candidate.getPerson().getId() == person.getId())
            .findFirst()
            .orElseThrow();
    }

    private Person sharedPersonForDeal(Deal deal, Person person) {
        return personMapper.getPersonsByDealId(workspace.getId(), deal.getId()).stream()
            .filter(candidate -> candidate.getId() == person.getId())
            .findFirst()
            .orElseThrow();
    }

    private TaskReminderCandidate taskCandidate(Task task) {
        return notificationMapper.findTaskReminderCandidates(workspace.getId()).stream()
            .filter(candidate -> candidate.getTaskId() == task.getId())
            .findFirst()
            .orElseThrow();
    }

    private List<RelationshipNudgeCandidate> nudgeCandidates(Deal deal) {
        return notificationMapper.findRelationshipNudgeCandidates(workspace.getId()).stream()
            .filter(candidate -> candidate.getDealId() == deal.getId())
            .toList();
    }

    private Task taskFor(User recipient, Person person, Deal deal) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Follow up " + unique());
        task.setStatus("todo");
        task.setDueDate("2026-07-31");
        task.setAssignedTo(recipient);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }

    private int orgId(Workspace candidate) {
        Integer orgId = workspaceMapper.getOrgId(candidate.getId());
        assertNotNull(orgId);
        return orgId;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Organization " + unique());
        organization.setSlug("org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace workspaceInOrg(int orgId) {
        Workspace candidate = new Workspace();
        candidate.setName("Workspace " + unique());
        candidate.setSlug("workspace-" + unique());
        candidate.setOrgId(orgId);
        workspaceMapper.insert(candidate);
        return candidate;
    }

    private Company companyIn(Workspace owner, String name) {
        Company company = new Company();
        company.setWorkspaceId(owner.getId());
        company.setName(name);
        companyMapper.insert(company);
        return company;
    }

    private Person personIn(Workspace owner, Company company, String name) {
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName(name);
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }
}

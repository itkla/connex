package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;

class PersonServiceTest extends AbstractServiceTest {

    @Autowired PersonService personService;
    @Autowired ShareMapper shareMapper;

    @Test
    void updateEvaluationExclusions_updatesOwnedContactOnly() {
        Person person = newPerson(newCompany());

        Person riskUpdated = personService.updateEvaluationExclusions(person.getId(), true, null);
        assertTrue(riskUpdated.isRiskExcluded());
        assertFalse(riskUpdated.isIntroExcluded());

        Person introUpdated = personService.updateEvaluationExclusions(person.getId(), null, true);
        assertTrue(introUpdated.isRiskExcluded());
        assertTrue(introUpdated.isIntroExcluded());

        Person idempotent = personService.updateEvaluationExclusions(person.getId(), true, true);
        assertTrue(idempotent.isRiskExcluded());
        assertTrue(idempotent.isIntroExcluded());
    }

    @Test
    void updateEvaluationExclusions_rejectsSharedInContact() {
        Workspace other = newOtherWorkspace();
        Person foreign = personInWorkspace(other);
        shareMapper.sharePerson(foreign.getId(), other.getId(), workspace.getId(), currentUser.getId(), true);

        assertTrue(personMapper.exists(workspace.getId(), foreign.getId()));
        assertFalse(personMapper.existsOwned(workspace.getId(), foreign.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> personService.updateEvaluationExclusions(foreign.getId(), true, true));
        Person ownerView = personMapper.getPersonById(other.getId(), foreign.getId());
        assertFalse(ownerView.isRiskExcluded());
        assertFalse(ownerView.isIntroExcluded());
    }

    @Test
    void getDealsByPersonId_returnsOnlyDealsLinkedToPerson() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal linked = newDeal(pipeline, stage, company);
        Deal unlinked = newDeal(pipeline, stage, company);
        Person person = newPerson(company);
        dealMapper.addPerson(workspace.getId(), linked.getId(), person.getId(), null);

        List<Deal> deals = personService.getDealsByPersonId(person.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == linked.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == unlinked.getId()));
    }

    @Test
    void getDealsByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getDealsByPersonId(-1));
    }

    @Test
    void getActivitiesByPersonId_returnsOnlyMatchingActivities() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Activity a1 = newActivity(user, p1, null);
        Activity a2 = newActivity(user, p2, null);

        List<Activity> activities = personService.getActivitiesByPersonId(p1.getId());

        assertTrue(activities.stream().anyMatch(x -> x.getId() == a1.getId()));
        assertTrue(activities.stream().noneMatch(x -> x.getId() == a2.getId()));
    }

    @Test
    void getActivitiesByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getActivitiesByPersonId(-1));
    }

    @Test
    void getNotesByPersonId_returnsOnlyMatchingNotes() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Note n1 = newNote(user, p1, null);
        Note n2 = newNote(user, p2, null);

        List<Note> notes = personService.getNotesByPersonId(p1.getId());

        assertTrue(notes.stream().anyMatch(x -> x.getId() == n1.getId()));
        assertTrue(notes.stream().noneMatch(x -> x.getId() == n2.getId()));
    }

    @Test
    void getNotesByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getNotesByPersonId(-1));
    }

    @Test
    void getTasksByPersonId_returnsOnlyMatchingTasks() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        User user = newUser();
        Task t1 = newTask(user, p1, null);
        Task t2 = newTask(user, p2, null);

        List<Task> tasks = personService.getTasksByPersonId(p1.getId());

        assertTrue(tasks.stream().anyMatch(x -> x.getId() == t1.getId()));
        assertTrue(tasks.stream().noneMatch(x -> x.getId() == t2.getId()));
    }

    @Test
    void getTasksByPersonId_throwsWhenPersonMissing() {
        assertThrows(ResourceNotFoundException.class, () -> personService.getTasksByPersonId(-1));
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
}

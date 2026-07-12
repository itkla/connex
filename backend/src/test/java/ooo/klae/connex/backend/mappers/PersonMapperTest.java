package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class PersonMapperTest extends AbstractMapperTest {

    @Autowired private NoteMapper noteMapper;

    /**
     * Inserts a new person and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Person person = newPerson(newCompany());
        assertNotEquals(0, person.getId());
    }

    /**
     * Gets a person by ID and checks if the returned person is not null.
     */
    @Test
    void getPersonById_returnsInsertedRow() {
        Company company = newCompany();
        Person person = newPerson(company);

        Person found = personMapper.getPersonById(workspace.getId(), person.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(person.getName(), found.getName());
        assertEquals(person.getEmail(), found.getEmail());
        assertEquals(person.getPhone(), found.getPhone());
        assertEquals("Engineer", found.getTitle());
        assertNotNull(found.getCompany());
        assertEquals(company.getId(), found.getCompany().getId());
    }

    @Test
    void getPersonByIdDoesNotEmbedPrivateNoteIdentifiers() {
        Person person = newPerson(newCompany());
        addNote(person, newUser(), "private");

        Person found = personMapper.getPersonById(workspace.getId(), person.getId());

        assertNull(found.getNotes());
    }

    /**
     * Gets a person by ID and checks if the returned person is null when the ID is missing.
     */
    @Test
    void getPersonById_returnsNullWhenMissing() {
        assertNull(personMapper.getPersonById(workspace.getId(), -1));
    }

    /**
     * The evaluation opt-out update is partial (a null flag stays unchanged) and workspace-scoped.
     */
    @Test
    void updateEvaluationExclusions_isPartialAndWorkspaceScoped() {
        Person person = newPerson(newCompany());
        Person initial = personMapper.getPersonById(workspace.getId(), person.getId());
        assertFalse(initial.isRiskExcluded());
        assertFalse(initial.isIntroExcluded());

        assertEquals(1, personMapper.updateEvaluationExclusions(workspace.getId(), person.getId(), true, null));
        Person updated = personMapper.getPersonById(workspace.getId(), person.getId());
        assertTrue(updated.isRiskExcluded());
        assertFalse(updated.isIntroExcluded());

        assertEquals(1, personMapper.updateEvaluationExclusions(workspace.getId(), person.getId(), null, true));
        updated = personMapper.getPersonById(workspace.getId(), person.getId());
        assertTrue(updated.isRiskExcluded());
        assertTrue(updated.isIntroExcluded());

        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        assertEquals(0, personMapper.updateEvaluationExclusions(other.getId(), person.getId(), false, false));
        assertTrue(personMapper.getPersonById(workspace.getId(), person.getId()).isRiskExcluded());
    }

    /**
     * Gets all persons and checks if the returned list includes the inserted person.
     */
    @Test
    void getAllPersons_includesInsertedRow() {
        Person person = newPerson(newCompany());

        List<Person> all = personMapper.getAllPersons(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == person.getId()));
    }

    @Test
    void getExistingPersonIdsIsWorkspaceScopedAndIgnoresMissingIds() {
        Person included = newPerson(newCompany());
        Workspace other = newWorkspace();
        Person foreign = newPersonIn(other);

        List<Integer> ids = personMapper.getExistingPersonIds(
            workspace.getId(), List.of(included.getId(), foreign.getId(), Integer.MAX_VALUE));

        assertEquals(List.of(included.getId()), ids);
    }

    @Test
    void getPersonsPageLimitsAndCountsWorkspaceRows() {
        Workspace pageWorkspace = newWorkspace();
        Person first = newPersonIn(pageWorkspace);
        Person second = newPersonIn(pageWorkspace);
        Person third = newPersonIn(pageWorkspace);
        Person foreign = newPerson(newCompany());

        List<Person> page = personMapper.getPersonsPage(
            pageWorkspace.getId(), null, null, null, null, null, false, 2, 0);

        assertEquals(2, page.size());
        assertEquals(3, personMapper.countPersons(pageWorkspace.getId(), null, null, null, false));
        assertTrue(page.stream().noneMatch(person -> person.getId() == foreign.getId()));
        assertTrue(page.stream().allMatch(person -> List.of(first.getId(), second.getId(), third.getId()).contains(person.getId())));
    }

    @Test
    void getPersonIdsFilteredAppliesLimitAndWorkspaceScope() {
        Workspace pageWorkspace = newWorkspace();
        Person first = newPersonIn(pageWorkspace);
        Person second = newPersonIn(pageWorkspace);
        newPersonIn(pageWorkspace);
        Person foreign = newPerson(newCompany());

        List<Integer> ids = personMapper.getPersonIdsFiltered(
            pageWorkspace.getId(), null, null, null, false, 2);

        assertEquals(List.of(first.getId(), second.getId()), ids);
        assertFalse(ids.contains(foreign.getId()));
    }

    /**
     * Updates a person and checks if the new values are persisted
     */
    @Test
    void update_persistsNewValues() {
        Company company = newCompany();
        Person person = newPerson(company);
        person.setName("Renamed Person");
        person.setTitle("Director");
        person.setCompany(null);

        personMapper.update(person);

        Person found = personMapper.getPersonById(workspace.getId(), person.getId());
        assertEquals("Renamed Person", found.getName());
        assertEquals("Director", found.getTitle());
        assertNull(found.getCompany());
    }

    /**
     * Deletes a person and checks if the person is removed.
     */
    @Test
    void delete_removesRow() {
        Person person = newPerson(newCompany());

        personMapper.delete(workspace.getId(), person.getId());

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()));
    }

    /**
     * Gets persons by company ID and checks if the returned list includes the inserted person.
     */
    @Test
    void getPersonsByCompanyId_returnsOnlyMatchingPeople() {
        Company company1 = newCompany();
        Company company2 = newCompany();
        Person person1 = newPerson(company1);
        Person person2 = newPerson(company2);
        addNote(person1, newUser(), "private");

        List<Person> in1 = personMapper.getPersonsByCompanyId(workspace.getId(), company1.getId(), null);

        assertTrue(in1.stream().anyMatch(x -> x.getId() == person1.getId()));
        assertTrue(in1.stream().noneMatch(x -> x.getId() == person2.getId()));
        assertNull(in1.stream().filter(x -> x.getId() == person1.getId()).findFirst().orElseThrow().getNotes());
    }

    @Test
    void getEngagedPersonIdsExcludesPrivateNoteOnlyContacts() {
        Person privateOnly = newPerson(newCompany());
        Person workspaceVisible = newPerson(newCompany());
        User author = newUser();
        addNote(privateOnly, author, "private");
        addNote(workspaceVisible, author, "workspace");

        List<Integer> engaged = personMapper.getEngagedPersonIds(workspace.getId());

        assertFalse(engaged.contains(privateOnly.getId()));
        assertTrue(engaged.contains(workspaceVisible.getId()));
    }

    /**
     * Adds a tag to a person and checks if the returned list includes the inserted person.
     */
    @Test
    void addTag_thenGetPersonsByTagId_returnsPerson() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();

        personMapper.addTag(workspace.getId(), person.getId(), tag.getId());

        List<Person> matched = personMapper.getPersonsByTagId(workspace.getId(), tag.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == person.getId()));
    }

    private void addNote(Person person, User author, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Note " + unique());
        note.setVisibility(visibility);
        note.setAuthor(author);
        note.setPerson(person);
        noteMapper.insert(note);
    }

    /**
     * Adds a tag to a person and checks if the tag is added only once.
     */
    @Test
    void addTag_isIdempotent() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();

        personMapper.addTag(workspace.getId(), person.getId(), tag.getId());
        personMapper.addTag(workspace.getId(), person.getId(), tag.getId());

        long matching = personMapper.getPersonsByTagId(workspace.getId(), tag.getId()).stream()
                .filter(x -> x.getId() == person.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a tag from a person and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();
        personMapper.addTag(workspace.getId(), person.getId(), tag.getId());

        personMapper.removeTag(workspace.getId(), person.getId(), tag.getId());

        assertTrue(personMapper.getPersonsByTagId(workspace.getId(), tag.getId()).stream()
                .noneMatch(x -> x.getId() == person.getId()));
    }

    /**
     * A tag write issued with another workspace's id must not associate the tag.
     */
    @Test
    void addTag_fromAnotherWorkspace_doesNotAssociate() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();
        Workspace other = newWorkspace();

        int affected = personMapper.addTag(other.getId(), person.getId(), tag.getId());

        assertEquals(0, affected, "cross-workspace addTag must affect no rows");
        assertTrue(personMapper.getPersonsByTagId(workspace.getId(), tag.getId()).stream()
                .noneMatch(x -> x.getId() == person.getId()));
    }

    /**
     * Gets persons by deal ID and checks if the returned list includes the inserted person.
     */
    @Test
    void getPersonsByDealId_returnsLinkedPeople() {
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        List<Person> matched = personMapper.getPersonsByDealId(workspace.getId(), deal.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == person.getId()));
    }

    /**
     * A contact in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void people_areIsolatedByWorkspace() {
        Person mine = newPerson(newCompany());
        Workspace other = newWorkspace();
        Person foreign = newPersonIn(other);

        assertNull(personMapper.getPersonById(workspace.getId(), foreign.getId()));
        assertFalse(personMapper.exists(workspace.getId(), foreign.getId()));
        assertTrue(personMapper.getAllPersons(workspace.getId()).stream().noneMatch(p -> p.getId() == foreign.getId()));
        assertTrue(personMapper.getAllPersons(workspace.getId()).stream().anyMatch(p -> p.getId() == mine.getId()));

        assertEquals(0, personMapper.delete(workspace.getId(), foreign.getId()));
        assertTrue(personMapper.exists(other.getId(), foreign.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Person newPersonIn(Workspace ws) {
        Person p = new Person();
        p.setName("Person " + unique());
        p.setEmail(unique() + "@example.com");
        p.setWorkspaceId(ws.getId());
        personMapper.insert(p);
        return p;
    }
}

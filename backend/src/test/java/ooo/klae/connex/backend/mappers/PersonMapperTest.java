package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

class PersonMapperTest extends AbstractMapperTest {

    @Autowired private NoteMapper noteMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void ownerRoundTripsThroughInsertAndGeneralUpdateCannotChangeIt() {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(41);
        person.setName("Owned " + unique());
        person.setEmail(unique() + "@example.com");
        personMapper.insert(person);

        assertEquals(41, personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());

        person.setOwnerId(42);
        person.setName("Renamed " + unique());
        personMapper.update(person);

        Person updated = personMapper.getPersonById(workspace.getId(), person.getId());
        assertEquals(41, updated.getOwnerId());
        assertEquals(person.getName(), updated.getName());
    }

    @Test
    void batchInsertPersistsOwnersAndUnassignedRows() {
        Person owned = personForBatch(workspace, "Owned batch", 51);
        Person unassigned = personForBatch(workspace, "Unassigned batch", null);

        assertEquals(2, personMapper.insertBatch(List.of(owned, unassigned)));

        List<Person> inserted = personMapper.getAllPersons(workspace.getId()).stream()
            .filter(person -> person.getName().endsWith("batch"))
            .toList();
        assertEquals(51, inserted.stream()
            .filter(person -> person.getName().equals("Owned batch"))
            .findFirst().orElseThrow().getOwnerId());
        assertNull(inserted.stream()
            .filter(person -> person.getName().equals("Unassigned batch"))
            .findFirst().orElseThrow().getOwnerId());
    }

    @Test
    void ownerMutationsAreWorkspaceScopedAndAccountErasureClearsEveryWorkspace() {
        Workspace target = newWorkspace();
        Workspace other = newWorkspace();
        Person targetOwned = newPersonIn(target);
        Person targetOtherOwner = newPersonIn(target);
        Person foreignOwned = newPersonIn(other);
        personMapper.updateOwner(target.getId(), targetOwned.getId(), 61);
        personMapper.updateOwner(target.getId(), targetOtherOwner.getId(), 62);
        personMapper.updateOwner(other.getId(), foreignOwned.getId(), 61);

        assertEquals(0, personMapper.updateOwner(other.getId(), targetOwned.getId(), 62));
        personMapper.clearMemberOwnership(target.getId(), 61);
        assertNull(personMapper.getPersonById(target.getId(), targetOwned.getId()).getOwnerId());
        assertEquals(62, personMapper.getPersonById(target.getId(), targetOtherOwner.getId()).getOwnerId());
        assertEquals(61, personMapper.getPersonById(other.getId(), foreignOwned.getId()).getOwnerId());

        personMapper.clearOwnershipAnywhere(61);
        assertNull(personMapper.getPersonById(other.getId(), foreignOwned.getId()).getOwnerId());
        assertEquals(62, personMapper.getPersonById(target.getId(), targetOtherOwner.getId()).getOwnerId());
    }

    @Test
    void ownerScopesAndFacetCountsMatchPageAndCountQueries() {
        Workspace target = newWorkspace();
        Person mine = newPersonIn(target);
        Person selected = newPersonIn(target);
        Person selectedToo = newPersonIn(target);
        Person unassigned = newPersonIn(target);
        Person other = newPersonIn(target);
        personMapper.updateOwner(target.getId(), mine.getId(), 71);
        personMapper.updateOwner(target.getId(), selected.getId(), 72);
        personMapper.updateOwner(target.getId(), selectedToo.getId(), 73);
        personMapper.updateOwner(target.getId(), other.getId(), 74);

        assertOwnerScope(target, MemberScope.fromRequest("me", null, 71), List.of(mine.getId()));
        assertOwnerScope(target, MemberScope.fromRequest("members", List.of(72, 73), 71),
            List.of(selected.getId(), selectedToo.getId()));
        assertOwnerScope(target, MemberScope.fromRequest("unassigned", null, 71),
            List.of(unassigned.getId()));
        assertEquals(Map.of("71", 1L, "72", 1L, "73", 1L, "74", 1L, "__empty__", 1L),
            facetCounts(personMapper.countsByOwner(target.getId())));
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

    @Test
    void updateProcessingRestrictionsPreservesTimestampsClearsIndependentlyAndIsWorkspaceScoped() {
        Person person = newPerson(newCompany());

        assertEquals(1, personMapper.updateProcessingRestrictions(
            workspace.getId(), person.getId(), true, true));
        Person restricted = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNotNull(restricted.getSuspendedAt());
        assertNotNull(restricted.getProvisionCeasedAt());

        LocalDateTime preserved = LocalDateTime.parse("2025-01-02T03:04:05");
        jdbcTemplate.update("UPDATE person SET suspended_at = ? WHERE id = ?", preserved, person.getId());
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, true);
        assertEquals(preserved, personMapper.getPersonById(workspace.getId(), person.getId()).getSuspendedAt());

        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), false, true);
        restricted = personMapper.getPersonById(workspace.getId(), person.getId());
        assertNull(restricted.getSuspendedAt());
        assertNotNull(restricted.getProvisionCeasedAt());

        Workspace other = newWorkspace();
        assertEquals(0, personMapper.updateProcessingRestrictions(other.getId(), person.getId(), true, false));
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getSuspendedAt());
        assertNotNull(personMapper.getPersonById(workspace.getId(), person.getId()).getProvisionCeasedAt());
    }

    @Test
    void suspendedContactsAreExcludedOnlyFromProcessingReads() {
        Company company = newCompany();
        Person normal = newPerson(company);
        Person suspended = newPerson(company);
        Person provisionCeased = newPerson(company);
        User author = newUser();
        addNote(normal, author, "workspace");
        addNote(suspended, author, "workspace");
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), suspended.getId(), "suspended");
        dealMapper.addPerson(workspace.getId(), deal.getId(), provisionCeased.getId(), "ceased");
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), provisionCeased.getId(), false, true);

        List<Integer> processableIds = personMapper.getProcessablePersons(workspace.getId()).stream()
            .map(Person::getId).toList();
        assertTrue(processableIds.contains(normal.getId()));
        assertTrue(processableIds.contains(provisionCeased.getId()));
        assertFalse(processableIds.contains(suspended.getId()));
        assertFalse(personMapper.getProcessablePersonIds(
            workspace.getId(), List.of(normal.getId(), suspended.getId(), provisionCeased.getId()))
            .contains(suspended.getId()));
        assertFalse(personMapper.getPersonsByCompanyIds(workspace.getId(), List.of(company.getId())).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));
        assertFalse(personMapper.getRelationshipScoreAggregates(
            workspace.getId(), LocalDateTime.now().plusDays(1),
            RelationshipWarmthModel.current().sqlParameters()).stream()
            .anyMatch(score -> score.id() == suspended.getId()));
        assertFalse(personMapper.getEngagedPersonIds(workspace.getId()).contains(suspended.getId()));
        assertFalse(personMapper.getPersonsForNetworkReport(workspace.getId(), 10_000).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));
        assertFalse(personMapper.getPersonsFiltered(
            workspace.getId(), null, null, null, false, allTeamScope(), false).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));

        assertNotNull(personMapper.getPersonById(workspace.getId(), suspended.getId()));
        assertTrue(personMapper.getAllPersons(workspace.getId()).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));
        assertTrue(personMapper.getPersonsPage(
            workspace.getId(), null, null, null, null, null, false, allTeamScope(), false, 10_000, 0).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));
        assertTrue(personMapper.getPersonsByDealId(workspace.getId(), deal.getId()).stream()
            .anyMatch(person -> person.getId() == suspended.getId()));
        assertFalse(dealMapper.getDealPeopleByDealId(workspace.getId(), deal.getId()).stream()
            .anyMatch(dealPerson -> dealPerson.getPerson().getId() == suspended.getId()));
        assertFalse(dealMapper.getDealPeopleByDealId(workspace.getId(), deal.getId()).stream()
            .anyMatch(dealPerson -> dealPerson.getPerson().getId() == provisionCeased.getId()));
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
    void getProcessablePersonIdsIsWorkspaceScopedAndIgnoresMissingIds() {
        Person included = newPerson(newCompany());
        Workspace other = newWorkspace();
        Person foreign = newPersonIn(other);

        List<Integer> ids = personMapper.getProcessablePersonIds(
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
            pageWorkspace.getId(), null, null, null, null, null, false, allTeamScope(), false, 2, 0);

        assertEquals(2, page.size());
        assertEquals(3, personMapper.countPersons(
            pageWorkspace.getId(), null, null, null, false, allTeamScope(), false));
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
            pageWorkspace.getId(), null, null, null, false, allTeamScope(), false, 2);

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

    @Test
    void genericUpdateCannotReplaceManagedImageAndCasRejectsStaleReplacement() {
        Person person = newPerson(newCompany());
        String first = "/api/persons/" + person.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440000.png";
        String second = "/api/persons/" + person.getId()
            + "/profile-picture/550e8400-e29b-41d4-a716-446655440001.png";
        assertEquals(1, personMapper.updateImageUrlIfCurrent(
            workspace.getId(), person.getId(), null, first));

        person.setImageUrl("https://attacker.example/image.png");
        personMapper.update(person);

        assertEquals(first,
            personMapper.getPersonById(workspace.getId(), person.getId()).getImageUrl());
        assertEquals(0, personMapper.updateImageUrlIfCurrent(
            workspace.getId(), person.getId(), null, second));
        assertEquals(1, personMapper.updateImageUrlIfCurrent(
            workspace.getId(), person.getId(), first, second));
    }

    /**
     * Archives a contact and checks it leaves the ordinary reads while the row survives.
     */
    @Test
    void archive_hidesRowAndRestoreBringsItBack() {
        Person person = newPerson(newCompany());

        assertEquals(1, personMapper.archive(workspace.getId(), person.getId()));

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()));
        assertFalse(personMapper.exists(workspace.getId(), person.getId()));
        assertFalse(personMapper.existsOwned(workspace.getId(), person.getId()));
        assertTrue(personMapper.existsOwnedArchived(workspace.getId(), person.getId()));
        assertNotNull(personMapper.getOwnedArchivedPersonById(workspace.getId(), person.getId()));

        assertEquals(1, personMapper.restore(workspace.getId(), person.getId()));

        assertNotNull(personMapper.getPersonById(workspace.getId(), person.getId()));
        assertTrue(personMapper.existsOwned(workspace.getId(), person.getId()));
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

    /**
     * A tagged contact must be returned with its tags hydrated. The nested-collection result map
     * populates the {@code Tag[]} property via a nested select; an inline join maps into a
     * {@code List} that cannot be assigned to the array, so a tagged contact would otherwise fail.
     */
    @Test
    void getPersonsByCompanyId_hydratesTagsForTaggedContact() {
        Company company = newCompany();
        Person person = newPerson(company);
        Tag tag1 = newTag();
        Tag tag2 = newTag();
        personMapper.addTag(workspace.getId(), person.getId(), tag1.getId());
        personMapper.addTag(workspace.getId(), person.getId(), tag2.getId());

        List<Person> people = personMapper.getPersonsByCompanyId(workspace.getId(), company.getId(), null);

        Person returned = people.stream().filter(x -> x.getId() == person.getId()).findFirst().orElseThrow();
        assertNotNull(returned.getTags());
        assertEquals(2, returned.getTags().length);
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
        User owner = newUser();
        personMapper.updateOwner(workspace.getId(), person.getId(), owner.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        List<Person> matched = personMapper.getPersonsByDealId(workspace.getId(), deal.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == person.getId()));
        assertEquals(owner.getId(), dealMapper.getDealPeopleByDealId(
            workspace.getId(), deal.getId()).getFirst().getPerson().getOwnerId());
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

        assertEquals(0, personMapper.archive(workspace.getId(), foreign.getId()));
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

    private Person personForBatch(Workspace ws, String name, Integer ownerId) {
        Person person = new Person();
        person.setWorkspaceId(ws.getId());
        person.setOwnerId(ownerId);
        person.setName(name);
        person.setEmail(unique() + "@example.com");
        return person;
    }

    private void assertOwnerScope(Workspace ws, MemberScope memberScope, List<Integer> expectedIds) {
        List<Integer> actualIds = personMapper.getPersonsPage(
            ws.getId(), null, "name", "asc", null, null, false, memberScope, false, 100, 0)
            .stream().map(Person::getId).toList();
        assertEquals(expectedIds.stream().sorted().toList(), actualIds.stream().sorted().toList());
        assertEquals(expectedIds.size(), personMapper.countPersons(
            ws.getId(), null, null, null, false, memberScope, false));
    }

    private Map<String, Long> facetCounts(List<FacetCount> counts) {
        return counts.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }

    private MemberScope allTeamScope() {
        return MemberScope.allTeam();
    }
}

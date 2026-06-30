package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

/**
 * Database-backed tests for {@link IntroductionService}: suggestion lifecycle, lineage, and the
 * tenant-scoping invariants on reads and writes.
 */
class IntroductionServiceTest extends AbstractServiceTest {

    @Autowired private IntroductionService introductionService;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private IntroductionMapper introductionMapper;

    @Test
    void suggestsSharedCompanyPairThenExcludesAfterRecording() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);

        assertTrue(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));

        IntroductionDto recorded = introductionService.createIntroduction(
            p1.getId(), p2.getId(), "Met at conference");
        assertNotNull(recorded);
        assertEquals("Met at conference", recorded.getNote());

        assertTrue(lineageHasPair(p1.getId(), p2.getId()));
        assertTrue(edgeExists(p1.getId(), p2.getId()));
        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));
    }

    @Test
    void suggestsMutualConnectionPair() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());

        List<IntroSuggestionDto> suggestions = introductionService.getSuggestions(50);
        IntroSuggestionDto pair = find(suggestions, p1.getId(), p2.getId());
        assertEquals(1, pair.getMutualConnections());
        assertTrue(pair.getReasons().contains("mutual_connections"));
    }

    @Test
    void dismissExcludesFromSuggestionsWithoutAddingLineage() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);
        assertTrue(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));

        introductionService.dismissSuggestion(p1.getId(), p2.getId());

        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));
        assertFalse(lineageHasPair(p1.getId(), p2.getId()));
    }

    @Test
    void recordIntroductionRejectsForeignWorkspaceContact() {
        Person p1 = engagedPerson(newCompany());
        Person foreign = foreignPerson();
        assertThrows(ResourceNotFoundException.class,
            () -> introductionService.createIntroduction(p1.getId(), foreign.getId(), null));
    }

    @Test
    void suggestionsAndLineageStayWorkspaceScoped() {
        Workspace other = newWorkspace();
        Company foreignCo = new Company();
        foreignCo.setName("ForeignCo " + unique());
        foreignCo.setWorkspaceId(other.getId());
        companyMapper.insert(foreignCo);
        Person f1 = foreignEngagedPerson(other, foreignCo);
        Person f2 = foreignEngagedPerson(other, foreignCo);

        Introduction foreignIntro = new Introduction();
        foreignIntro.setWorkspaceId(other.getId());
        foreignIntro.setIntroducerUserId(currentUser.getId());
        foreignIntro.setPersonAId(Math.min(f1.getId(), f2.getId()));
        foreignIntro.setPersonBId(Math.max(f1.getId(), f2.getId()));
        foreignIntro.setIntroducedAt("2026-06-01 00:00:00");
        introductionMapper.recordMade(foreignIntro);

        assertFalse(hasPair(introductionService.getSuggestions(50), f1.getId(), f2.getId()));
        assertFalse(lineageHasPair(f1.getId(), f2.getId()));
    }

    @Test
    void recordIntroductionIsIdempotentAndKeepsLatestNote() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);

        introductionService.createIntroduction(p1.getId(), p2.getId(), "first");
        introductionService.createIntroduction(p2.getId(), p1.getId(), "second");

        List<IntroductionDto> rows = introductionService.getLineage(1, 50).items().stream()
            .filter(i -> matchesPair(i, p1.getId(), p2.getId()))
            .toList();
        assertEquals(1, rows.size());
        assertEquals("second", rows.get(0).getNote());
    }

    @Test
    void recordIntroductionDoesNotDowngradeExistingStrongerEdge() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);
        PersonEdge friend = new PersonEdge();
        friend.setWorkspaceId(workspace.getId());
        friend.setSourcePersonId(Math.min(p1.getId(), p2.getId()));
        friend.setTargetPersonId(Math.max(p1.getId(), p2.getId()));
        friend.setType("friend");
        friend.setStrength(3);
        personEdgeMapper.upsert(friend);

        introductionService.createIntroduction(p1.getId(), p2.getId(), null);

        PersonEdge edge = personEdgeMapper.getAllEdges(workspace.getId()).stream()
            .filter(e -> e.getSourcePersonId() == Math.min(p1.getId(), p2.getId())
                && e.getTargetPersonId() == Math.max(p1.getId(), p2.getId()))
            .findFirst().orElseThrow();
        assertEquals("friend", edge.getType());
        assertEquals(3, edge.getStrength());
    }

    @Test
    void cannotIntroduceContactToItself() {
        Person p1 = engagedPerson(newCompany());
        assertThrows(BadRequestException.class,
            () -> introductionService.createIntroduction(p1.getId(), p1.getId(), null));
    }

    private Person engagedPerson(Company company) {
        Person person = newPerson(company);
        newActivity(currentUser, person, null);
        return person;
    }

    private void connect(int a, int b) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(a, b));
        edge.setTargetPersonId(Math.max(a, b));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);
    }

    private Workspace newWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Person foreignPerson() {
        Person person = new Person();
        person.setName("Foreign " + unique());
        person.setWorkspaceId(newWorkspace().getId());
        personMapper.insert(person);
        return person;
    }

    private Person foreignEngagedPerson(Workspace ws, Company company) {
        Person person = new Person();
        person.setName("Foreign " + unique());
        person.setWorkspaceId(ws.getId());
        person.setCompany(company);
        personMapper.insert(person);
        Activity activity = new Activity();
        activity.setWorkspaceId(ws.getId());
        activity.setType("call");
        activity.setSubject("subj " + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp("2026-06-01 10:00:00");
        activityMapper.insert(activity);
        return person;
    }

    private boolean hasPair(List<IntroSuggestionDto> suggestions, int a, int b) {
        int lower = Math.min(a, b);
        int higher = Math.max(a, b);
        return suggestions.stream()
            .anyMatch(s -> s.getPersonAId() == lower && s.getPersonBId() == higher);
    }

    private IntroSuggestionDto find(List<IntroSuggestionDto> suggestions, int a, int b) {
        int lower = Math.min(a, b);
        int higher = Math.max(a, b);
        return suggestions.stream()
            .filter(s -> s.getPersonAId() == lower && s.getPersonBId() == higher)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected suggestion for " + a + "-" + b));
    }

    private boolean lineageHasPair(int a, int b) {
        return introductionService.getLineage(1, 50).items().stream()
            .anyMatch(i -> matchesPair(i, a, b));
    }

    private static boolean matchesPair(IntroductionDto dto, int a, int b) {
        return dto.getPersonAId() == Math.min(a, b) && dto.getPersonBId() == Math.max(a, b);
    }

    private boolean edgeExists(int a, int b) {
        return personEdgeMapper.getAllEdges(workspace.getId()).stream()
            .anyMatch(e -> e.getSourcePersonId() == Math.min(a, b)
                && e.getTargetPersonId() == Math.max(a, b));
    }
}

package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.IntroOverviewDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonEmploymentMapper;

/**
 * Database-backed tests for {@link IntroductionService}: suggestion lifecycle, lineage, and the
 * tenant-scoping invariants on reads and writes.
 */
class IntroductionServiceTest extends AbstractServiceTest {

    @Autowired private IntroductionService introductionService;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private PersonEdgeReadService personEdgeReader;
    @Autowired private IntroductionMapper introductionMapper;
    @Autowired private PersonEmploymentMapper personEmploymentMapper;

    @Test
    void suggestsPairThenExcludesAfterRecording() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());

        assertTrue(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));

        IntroductionDto recorded = introductionService.createIntroduction(
            p1.getId(), p2.getId(), "Met at conference");
        assertNotNull(recorded);
        assertEquals("Met at conference", recorded.getNote());
        assertEquals(currentUser.getDisplayName(), recorded.getIntroducerName());

        assertTrue(lineageHasPair(p1.getId(), p2.getId()));
        assertTrue(edgeExists(p1.getId(), p2.getId()));
        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));
    }

    @Test
    void explicitGraphEvidenceQualifiesCandidatesWithoutLoggedActivity() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());

        IntroSuggestionDto pair = find(introductionService.getSuggestions(50), p1.getId(), p2.getId());

        assertEquals(List.of(hub.getId()), pair.getSupportingPersonIds());
        assertEquals(2, pair.getSupportingEdgeIds().size());
    }

    @Test
    void overviewServesBothFeedsFromOneWarmthPass() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());

        Person bridge = recentlyEngagedPerson(newCompany());
        Person target = newPerson(newCompany());
        connect(bridge.getId(), target.getId());

        IntroOverviewDto overview = introductionService.getOverview(50, 50);

        assertTrue(hasPair(overview.getSuggestions(), p1.getId(), p2.getId()),
            "the overview must carry the give-side suggestions");
        assertTrue(overview.getPaths().stream().anyMatch(row -> row.getTargetId() == target.getId()),
            "the overview must carry the receive-side warm paths");
        assertNotNull(overview.getAsOf());
        assertTrue(overview.getSuggestions().stream()
            .allMatch(suggestion -> overview.getAsOf().equals(suggestion.getAsOf())));
        assertTrue(overview.getPaths().stream()
            .allMatch(path -> overview.getAsOf().equals(path.getAsOf())));
    }

    @Test
    void overviewExplainsMissingRelationshipEvidence() {
        engagedPerson(newCompany());
        engagedPerson(newCompany());

        IntroOverviewDto noEvidence = introductionService.getOverview(50, 50);

        assertEquals(IntroductionService.EMPTY_MISSING_RELATIONSHIP_EVIDENCE,
            noEvidence.getSuggestionsEmptyReason());
    }

    @Test
    void overviewExplainsPolicyExclusion() {
        Person excludedA = engagedPerson(newCompany());
        Person excludedB = engagedPerson(newCompany());
        personMapper.updateEvaluationExclusions(workspace.getId(), excludedA.getId(), null, true);
        personMapper.updateEvaluationExclusions(workspace.getId(), excludedB.getId(), null, true);

        IntroOverviewDto restricted = introductionService.getOverview(50, 50);

        assertEquals(IntroductionService.EMPTY_POLICY_EXCLUSION,
            restricted.getSuggestionsEmptyReason());
        assertEquals(IntroductionService.EMPTY_POLICY_EXCLUSION,
            restricted.getPathsEmptyReason());
    }

    @Test
    void overviewExplainsPolicyExclusionWhenConnectorIsRestricted() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());
        personMapper.updateEvaluationExclusions(workspace.getId(), hub.getId(), null, true);

        IntroOverviewDto restricted = introductionService.getOverview(50, 50);

        assertTrue(restricted.getSuggestions().isEmpty());
        assertEquals(IntroductionService.EMPTY_POLICY_EXCLUSION,
            restricted.getSuggestionsEmptyReason());
    }

    @Test
    void doesNotSuggestSameEmployerContactsWithoutAnotherSignal() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);

        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()),
            "a shared current employer alone is not a reason to suggest an intro");
    }

    @Test
    void surfacesSameEmployerContactsWhenTheyShareAMutualConnection() {
        Company acme = newCompany();
        Person p1 = engagedPerson(acme);
        Person p2 = engagedPerson(acme);
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());

        IntroSuggestionDto pair = find(introductionService.getSuggestions(50), p1.getId(), p2.getId());
        assertEquals(1, pair.getMutualConnections());
        assertTrue(pair.getReasons().contains("mutual_connections"));
        assertEquals(null, pair.getSharedCompany());
    }

    @Test
    void suggestsFormerColleaguesViaEmploymentHistory() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Company past = newCompany();
        addEmployment(p1, past);
        addEmployment(p2, past);

        IntroSuggestionDto pair = find(introductionService.getSuggestions(50), p1.getId(), p2.getId());
        assertTrue(pair.getReasons().contains("shared_company"));
        assertEquals(past.getName(), pair.getSharedCompany());
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
    void suspendedMutualConnectorDoesNotCreateSuggestion() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());
        personMapper.updateProcessingRestrictions(workspace.getId(), hub.getId(), true, false);

        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));
    }

    @Test
    void introExcludedMutualConnectorDoesNotCreateSuggestion() {
        Person p1 = newPerson(newCompany());
        Person p2 = newPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());
        personMapper.updateEvaluationExclusions(workspace.getId(), hub.getId(), null, true);

        assertFalse(introductionMapper.findCandidatePersons(workspace.getId()).stream()
            .anyMatch(candidate -> candidate.getId() == p1.getId() || candidate.getId() == p2.getId()));
        assertFalse(hasPair(introductionService.getSuggestions(50), p1.getId(), p2.getId()));
    }

    @Test
    void dismissExcludesFromSuggestionsWithoutAddingLineage() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Person hub = newPerson(newCompany());
        connect(hub.getId(), p1.getId());
        connect(hub.getId(), p2.getId());
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
    void lineagePreservesTheDisplayNameOfADepartedIntroducer() {
        User departed = newUser();
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        Introduction introduction = introduction(p1, p2, departed.getId());
        introductionMapper.recordMade(introduction);
        workspaceMapper.removeMember(workspace.getId(), departed.getId());

        IntroductionDto recorded = introductionService.getLineage(1, 50).items().stream()
            .filter(item -> matchesPair(item, p1.getId(), p2.getId()))
            .findFirst()
            .orElseThrow();

        assertEquals(departed.getDisplayName(), recorded.getIntroducerName());
    }

    @Test
    void lineageKeepsRowsWhoseIntroducerAccountIsMissing() {
        Person p1 = engagedPerson(newCompany());
        Person p2 = engagedPerson(newCompany());
        int missingUserId = 2_000_000_000;
        introductionMapper.recordMade(introduction(p1, p2, missingUserId));

        IntroductionDto recorded = introductionService.getLineage(1, 50).items().stream()
            .filter(item -> matchesPair(item, p1.getId(), p2.getId()))
            .findFirst()
            .orElseThrow();

        assertEquals(missingUserId, recorded.getIntroducerId());
        assertEquals(null, recorded.getIntroducerName());
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

        PersonEdge edge = personEdgeReader.getAllEdges(workspace.getId()).stream()
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

    /** A contact touched today, so it scores warm/hot and qualifies as a warm-path bridge. */
    private Person recentlyEngagedPerson(Company company) {
        Person person = newPerson(company);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("subj_" + unique());
        activity.setNotes("notes_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        activityMapper.insert(activity);
        return person;
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

    private Introduction introduction(Person first, Person second, int introducerId) {
        Introduction introduction = new Introduction();
        introduction.setWorkspaceId(workspace.getId());
        introduction.setIntroducerUserId(introducerId);
        introduction.setPersonAId(Math.min(first.getId(), second.getId()));
        introduction.setPersonBId(Math.max(first.getId(), second.getId()));
        introduction.setIntroducedAt("2026-07-01 00:00:00");
        return introduction;
    }

    private void addEmployment(Person person, Company company) {
        PersonEmployment employment = new PersonEmployment();
        employment.setWorkspaceId(workspace.getId());
        employment.setPersonId(person.getId());
        employment.setCompanyId(company.getId());
        employment.setCompanyName(company.getName());
        employment.setTitle("Engineer");
        employment.setStartedAt("2018-01-01");
        employment.setEndedAt("2021-01-01");
        personEmploymentMapper.insert(employment);
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
        return personEdgeReader.getAllEdges(workspace.getId()).stream()
            .anyMatch(e -> e.getSourcePersonId() == Math.min(a, b)
                && e.getTargetPersonId() == Math.max(a, b));
    }
}

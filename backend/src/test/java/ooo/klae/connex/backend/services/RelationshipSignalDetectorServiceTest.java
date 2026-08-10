package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmPathBridgeDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

class RelationshipSignalDetectorServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final Instant AS_OF = Instant.parse("2026-08-08T12:00:00Z");

    private ScoringService scoringService;
    private DealRiskService dealRiskService;
    private WarmPathService warmPathService;
    private PersonMapper personMapper;
    private CompanyMapper companyMapper;
    private DealMapper dealMapper;
    private RelationshipSignalDetectorService detector;

    @BeforeEach
    void setUp() {
        scoringService = mock(ScoringService.class);
        dealRiskService = mock(DealRiskService.class);
        warmPathService = mock(WarmPathService.class);
        personMapper = mock(PersonMapper.class);
        companyMapper = mock(CompanyMapper.class);
        dealMapper = mock(DealMapper.class);
        detector = new RelationshipSignalDetectorService(
            scoringService,
            dealRiskService,
            warmPathService,
            personMapper,
            companyMapper,
            dealMapper,
            new ObjectMapper(),
            Clock.fixed(AS_OF, java.time.ZoneOffset.UTC));
        when(personMapper.getByIds(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<Integer> ids = invocation.getArgument(1);
                return ids.stream().map(RelationshipSignalDetectorServiceTest::person).toList();
            });
        when(companyMapper.getByIds(eq(WORKSPACE_ID), anyList())).thenReturn(List.of());
        when(scoringService.contactSourceStateHashes(
                WORKSPACE_ID, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(Map.of());
        when(scoringService.companySourceStateHashes(WORKSPACE_ID)).thenReturn(Map.of());
        when(scoringService.scoreContacts(WORKSPACE_ID)).thenReturn(List.of());
        when(dealMapper.getByIds(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<Integer> ids = invocation.getArgument(1);
                return ids.stream().map(RelationshipSignalDetectorServiceTest::deal).toList();
            });
    }

    @Test
    void threeFamiliesDelegateToCanonicalSourcesAndCarryExplainableEvidence() {
        List<RelationshipTemperatureDto> temperatures = new ArrayList<>();
        for (int id = 1; id <= 80; id++) {
            temperatures.add(temperature(id, (id - 1) % 30));
        }
        when(scoringService.scoreWorkspace(WORKSPACE_ID)).thenReturn(
            new ScoringService.WorkspaceScores(temperatures, List.of()));
        when(scoringService.contactSourceStateHashes(
                WORKSPACE_ID, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(Map.of());

        DealRiskDto risk = new DealRiskDto(
            91,
            new BigDecimal("1200.00"),
            "USD",
            "high",
            75,
            List.of(new DealRiskFactor(
                "stalled", "high", Map.of("daysSinceTouch", 42))),
            "2026-08-08 12:00:00");
        when(dealRiskService.assessWorkspaceNotificationStates(
                eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(List.of(new DealRiskService.NotificationRiskState(risk, "a".repeat(64))));

        WarmPathBridgeDto bridge = new WarmPathBridgeDto();
        bridge.setPersonId(2);
        bridge.setName("Bridge");
        bridge.setEvidenceType("connection");
        bridge.setScore(80);
        bridge.setSupportingPersonIds(List.of(2));
        bridge.setSupportingEdgeIds(List.of(11));
        WarmPathDto path = new WarmPathDto();
        path.setTargetId(3);
        path.setTargetName("Target");
        path.setReachType("reach");
        path.setScore(80);
        path.setBridges(List.of(bridge));
        path.setSourceState(List.of("persisted-path-state"));
        when(warmPathService.computePaths(eq(WORKSPACE_ID), eq(10), anyMap(), anyMap()))
            .thenReturn(List.of(path));

        RelationshipSignalDetectorService.Detection decay =
            detector.detectDecay(WORKSPACE_ID, "decay-token");
        RelationshipSignalDetectorService.Detection dealRisk =
            detector.detectDealRisk(WORKSPACE_ID, "risk-token");
        RelationshipSignalDetectorService.Detection warmPath =
            detector.detectWarmPaths(WORKSPACE_ID, "path-token", AS_OF);

        assertEquals(30, decay.candidates().size());
        assertEquals(30, decay.candidates().stream().map(signal -> signal.getDedupeKey()).distinct().count());
        assertTrue(decay.candidates().stream().allMatch(signal ->
            signal.getEvidenceAsOf().toInstant(java.time.ZoneOffset.UTC).equals(AS_OF)));
        assertTrue(decay.candidates().stream().allMatch(signal ->
            signal.getRankExplanationJson().contains("daysSinceTouch")));
        assertEquals("deal_risk:deal:91", dealRisk.candidates().getFirst().getDedupeKey());
        assertTrue(dealRisk.candidates().getFirst().getEvidenceJson().contains("stalled"));
        assertEquals("warm_path:person:3", warmPath.candidates().getFirst().getDedupeKey());
        assertTrue(warmPath.candidates().getFirst().getEvidenceJson().contains("Bridge"));
        assertTrue(warmPath.candidates().getFirst().getEvidenceJson().contains("reach"));
        verify(warmPathService).computePaths(eq(WORKSPACE_ID), eq(10), anyMap(), anyMap());
    }

    @Test
    void decayBoundIsStableWhenCandidatesGreatlyExceedTheCap() {
        List<RelationshipTemperatureDto> temperatures = new ArrayList<>();
        for (int id = 1; id <= 300; id++) {
            temperatures.add(temperature(id, (id - 1) % 30));
        }
        when(scoringService.scoreWorkspace(WORKSPACE_ID)).thenReturn(
            new ScoringService.WorkspaceScores(temperatures, List.of()));

        List<String> first = detector.detectDecay(WORKSPACE_ID, "one").candidates().stream()
            .map(signal -> signal.getDedupeKey()).toList();
        List<String> second = detector.detectDecay(WORKSPACE_ID, "two").candidates().stream()
            .map(signal -> signal.getDedupeKey()).toList();

        assertEquals(30, first.size());
        assertEquals(first, second);
    }

    @Test
    void coolingWithoutAColdPredictionRemainsAnHonestBoundedSignal() {
        RelationshipTemperatureDto cooling = new RelationshipTemperatureDto(
            404,
            42,
            "cool",
            "cooling",
            "2026-07-01 12:00:00",
            38,
            1,
            null,
            null,
            "warmth-v1",
            AS_OF);
        when(scoringService.scoreWorkspace(WORKSPACE_ID)).thenReturn(
            new ScoringService.WorkspaceScores(List.of(cooling), List.of()));

        var signal = detector.detectDecay(WORKSPACE_ID, "nullable").candidates().getFirst();

        assertEquals("cooling", signal.getPriority());
        assertTrue(signal.getEvidenceJson().contains("\"daysUntilCold\":null"));
        assertTrue(signal.getRankExplanationJson().contains("daysSinceTouch"));
    }

    @Test
    void dealRiskAndWarmPathCapsAreStableAboveTheirBounds() {
        when(scoringService.scoreWorkspace(WORKSPACE_ID)).thenReturn(
            new ScoringService.WorkspaceScores(List.of(), List.of()));
        when(scoringService.contactSourceStateHashes(
                WORKSPACE_ID, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(Map.of());
        List<DealRiskService.NotificationRiskState> risks = new ArrayList<>();
        for (int id = 1; id <= 80; id++) {
            DealRiskDto risk = new DealRiskDto(
                id,
                BigDecimal.ONE,
                "USD",
                "medium",
                100 - id,
                List.of(new DealRiskFactor("stalled", "medium", Map.of())),
                "2026-08-08 12:00:00");
            risks.add(new DealRiskService.NotificationRiskState(
                risk, String.format("%064x", id)));
        }
        when(dealRiskService.assessWorkspaceNotificationStates(
                eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(risks);

        List<WarmPathDto> paths = new ArrayList<>();
        for (int id = 1; id <= 40; id++) {
            WarmPathBridgeDto bridge = new WarmPathBridgeDto();
            bridge.setPersonId(1000 + id);
            bridge.setName("Bridge " + id);
            bridge.setEvidenceType("connection");
            bridge.setScore(100 - id);
            bridge.setSupportingPersonIds(List.of());
            bridge.setSupportingEdgeIds(List.of(id));
            WarmPathDto path = new WarmPathDto();
            path.setTargetId(id);
            path.setTargetName("Target " + id);
            path.setReachType("reach");
            path.setScore(100 - id);
            path.setBridges(List.of(bridge));
            path.setSourceState(List.of("persisted-path-state-" + id));
            paths.add(path);
        }
        when(warmPathService.computePaths(eq(WORKSPACE_ID), eq(10), anyMap(), anyMap()))
            .thenReturn(paths);

        List<String> firstRisks = detector.detectDealRisk(WORKSPACE_ID, "risk-one")
            .candidates().stream().map(signal -> signal.getDedupeKey()).toList();
        List<String> secondRisks = detector.detectDealRisk(WORKSPACE_ID, "risk-two")
            .candidates().stream().map(signal -> signal.getDedupeKey()).toList();
        List<String> firstPaths = detector.detectWarmPaths(WORKSPACE_ID, "path-one", AS_OF)
            .candidates().stream().map(signal -> signal.getDedupeKey()).toList();
        List<String> secondPaths = detector.detectWarmPaths(WORKSPACE_ID, "path-two", AS_OF)
            .candidates().stream().map(signal -> signal.getDedupeKey()).toList();

        assertEquals(20, firstRisks.size());
        assertEquals(firstRisks, secondRisks);
        assertEquals(10, firstPaths.size());
        assertEquals(firstPaths, secondPaths);
    }

    @Test
    void noWarmthDealRiskUsesItsAssessmentTimeInsteadOfEpoch() {
        when(scoringService.scoreWorkspace(WORKSPACE_ID)).thenReturn(
            new ScoringService.WorkspaceScores(List.of(), List.of()));
        when(scoringService.contactSourceStateHashes(
                WORKSPACE_ID, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(Map.of());
        DealRiskDto risk = new DealRiskDto(
            77,
            BigDecimal.TEN,
            "USD",
            "high",
            50,
            List.of(new DealRiskFactor("close_overdue", "high", Map.of())),
            "2026-08-08 12:00:00");
        when(dealRiskService.assessWorkspaceNotificationStates(
                eq(WORKSPACE_ID), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(List.of(new DealRiskService.NotificationRiskState(
                risk, "b".repeat(64))));

        var detected = detector.detectDealRisk(WORKSPACE_ID, "risk");

        assertEquals(AS_OF, detected.evidenceAsOf());
        assertEquals(AS_OF, detected.candidates().getFirst().getEvidenceAsOf()
            .toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void materialPersistedSourceChangesDismissalFingerprints() {
        RelationshipTemperatureDto before = temperature(12, 9);
        RelationshipTemperatureDto after = new RelationshipTemperatureDto(
            12,
            51,
            "warm",
            "cooling",
            before.getLastTouchAt(),
            before.getDaysSinceTouch(),
            before.getTouchCount() + 1,
            "2026-08-18",
            8,
            before.getModelVersion(),
            AS_OF);
        when(scoringService.scoreWorkspace(WORKSPACE_ID))
            .thenReturn(
                new ScoringService.WorkspaceScores(List.of(before), List.of()),
                new ScoringService.WorkspaceScores(List.of(after), List.of()));
        when(scoringService.contactSourceStateHashes(
                WORKSPACE_ID, java.util.Set.of(), java.util.Set.of(), java.util.Set.of()))
            .thenReturn(
                Map.of(12, "a".repeat(64)),
                Map.of(12, "b".repeat(64)));

        String decayBefore = detector.detectDecay(WORKSPACE_ID, "before")
            .candidates().getFirst().getSourceStateHash();
        String decayAfter = detector.detectDecay(WORKSPACE_ID, "after")
            .candidates().getFirst().getSourceStateHash();

        WarmPathBridgeDto bridge = new WarmPathBridgeDto();
        bridge.setPersonId(22);
        bridge.setName("Bridge");
        bridge.setEvidenceType("former_colleagues");
        bridge.setEvidenceCompany("Acme");
        bridge.setOverlapStartYear(2018);
        bridge.setOverlapEndYear(2020);
        bridge.setScore(70);
        bridge.setSupportingPersonIds(List.of());
        bridge.setSupportingEdgeIds(List.of(31));
        WarmPathDto path = new WarmPathDto();
        path.setTargetId(23);
        path.setTargetName("Target");
        path.setReachType("reach");
        path.setScore(70);
        path.setBridges(List.of(bridge));
        path.setSourceState(List.of("touch-state-before", "employment-state-before"));
        when(warmPathService.computePaths(eq(WORKSPACE_ID), eq(10), anyMap(), anyMap()))
            .thenReturn(List.of(path));
        String pathBefore = detector.detectWarmPaths(WORKSPACE_ID, "before", AS_OF)
            .candidates().getFirst().getSourceStateHash();
        bridge.setOverlapEndYear(2021);
        bridge.setScore(75);
        path.setScore(75);
        path.setSourceState(List.of("touch-state-before", "employment-state-after"));
        String pathAfter = detector.detectWarmPaths(WORKSPACE_ID, "after", AS_OF)
            .candidates().getFirst().getSourceStateHash();

        assertNotEquals(decayBefore, decayAfter);
        assertNotEquals(pathBefore, pathAfter);
    }

    @Test
    void identicalPersistedDecayFactsAtDifferentInstantsKeepTheFinalFingerprint() {
        Instant laterInstant = AS_OF.plusSeconds(14L * 86_400L);
        Person subject = person(12);
        Activity touch = activity(71, subject, "meeting", "2026-07-09 12:00:00");
        SourceScoringFixture fixture = sourceScoringFixture(List.of(subject), List.of(touch));
        when(fixture.personMapper().getRelationshipScoreAggregates(
                eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of(new RelationshipScoreAggregateDto(
                subject.getId(), 0.5, 0.0, 1.0, touch.getTimestamp(), 0)));
        ScoringService beforeScoring = fixture.serviceAt(AS_OF);
        ScoringService laterScoring = fixture.serviceAt(laterInstant);
        RelationshipTemperatureDto beforeTemperature =
            beforeScoring.scoreWorkspace(WORKSPACE_ID).contacts().getFirst();
        RelationshipTemperatureDto laterTemperature =
            laterScoring.scoreWorkspace(WORKSPACE_ID).contacts().getFirst();

        var first = detectorFor(beforeScoring, warmPathService, fixture, AS_OF)
            .detectDecay(WORKSPACE_ID, "before").candidates().getFirst();
        var second = detectorFor(laterScoring, warmPathService, fixture, laterInstant)
            .detectDecay(WORKSPACE_ID, "later").candidates().getFirst();

        assertNotEquals(beforeTemperature.getAsOf(), laterTemperature.getAsOf());
        assertNotEquals(beforeTemperature.getDaysSinceTouch(), laterTemperature.getDaysSinceTouch());
        assertNotEquals(beforeTemperature.getGoesColdAt(), laterTemperature.getGoesColdAt());
        assertNotEquals(first.getEvidenceJson(), second.getEvidenceJson());
        assertEquals(first.getSourceStateHash(), second.getSourceStateHash());
    }

    @Test
    void identicalPersistedWarmPathFactsAtDifferentInstantsKeepTheFinalFingerprint() {
        Instant laterInstant = AS_OF.plusSeconds(14L * 86_400L);
        Person bridgePerson = person(22);
        Person targetPerson = person(23);
        Activity touch = activity(72, bridgePerson, "meeting", "2026-08-07 12:00:00");
        SourceScoringFixture fixture = sourceScoringFixture(
            List.of(bridgePerson, targetPerson), List.of(touch));
        IntroductionMapper introductionMapper = mock(IntroductionMapper.class);
        PersonEdgeReadService edgeReader = mock(PersonEdgeReadService.class);
        when(introductionMapper.findWarmPathCandidates(WORKSPACE_ID)).thenReturn(List.of(
            introCandidate(bridgePerson.getId(), "Bridge"),
            introCandidate(targetPerson.getId(), "Target")));
        when(introductionMapper.findIntroExcludedPersonIds(WORKSPACE_ID)).thenReturn(List.of());
        when(introductionMapper.findWorkspaceEmployment(WORKSPACE_ID)).thenReturn(List.of());
        when(introductionMapper.findWarmPathDismissals(WORKSPACE_ID)).thenReturn(List.of());
        when(edgeReader.getAllEdges(WORKSPACE_ID)).thenReturn(List.of(
            edge(31, bridgePerson.getId(), targetPerson.getId(), 2)));
        ScoringService beforeScoring = fixture.serviceAt(AS_OF);
        ScoringService laterScoring = fixture.serviceAt(laterInstant);
        WarmPathService beforeWarmPaths = warmPathService(
            introductionMapper, edgeReader, fixture.personMapper(), beforeScoring, AS_OF);
        WarmPathService laterWarmPaths = warmPathService(
            introductionMapper, edgeReader, fixture.personMapper(), laterScoring, laterInstant);
        RelationshipTemperatureDto beforeTemperature = beforeScoring.scoreContacts(WORKSPACE_ID)
            .stream().filter(value -> value.getId() == bridgePerson.getId()).findFirst().orElseThrow();
        RelationshipTemperatureDto laterTemperature = laterScoring.scoreContacts(WORKSPACE_ID)
            .stream().filter(value -> value.getId() == bridgePerson.getId()).findFirst().orElseThrow();

        var first = detectorFor(beforeScoring, beforeWarmPaths, fixture, AS_OF)
            .detectWarmPaths(WORKSPACE_ID, "before", AS_OF).candidates().getFirst();
        var second = detectorFor(laterScoring, laterWarmPaths, fixture, laterInstant)
            .detectWarmPaths(WORKSPACE_ID, "later", laterInstant).candidates().getFirst();

        assertNotEquals(beforeTemperature.getAsOf(), laterTemperature.getAsOf());
        assertNotEquals(beforeTemperature.getDaysSinceTouch(), laterTemperature.getDaysSinceTouch());
        assertNotEquals(beforeTemperature.getScore(), laterTemperature.getScore());
        assertNotEquals(first.getEvidenceJson(), second.getEvidenceJson());
        assertEquals(first.getSourceStateHash(), second.getSourceStateHash());
    }

    @Test
    void dismissingTheLeadingBridgeChangesTheFinalWarmPathFingerprint() {
        IntroCandidatePerson leadingBridge = introCandidate(21, "Leading bridge");
        IntroCandidatePerson remainingBridge = introCandidate(22, "Remaining bridge");
        IntroCandidatePerson target = introCandidate(23, "Target");
        List<IntroCandidatePerson> candidates = List.of(leadingBridge, remainingBridge, target);
        List<PersonEdge> edges = List.of(
            edge(31, leadingBridge.getId(), target.getId(), 2),
            edge(32, remainingBridge.getId(), target.getId(), 2));
        Map<Integer, RelationshipTemperatureDto> temperatures = Map.of(
            leadingBridge.getId(), warmTemperature(leadingBridge.getId(), 80),
            remainingBridge.getId(), warmTemperature(remainingBridge.getId(), 60));
        Map<Integer, String> sourceHashes = Map.of(
            leadingBridge.getId(), "a".repeat(64),
            remainingBridge.getId(), "b".repeat(64),
            target.getId(), "c".repeat(64));
        WarmPathDto before = WarmPathService.rankPaths(
            candidates, edges, List.of(), List.of(), temperatures, sourceHashes, 10).getFirst();
        WarmPathDto after = WarmPathService.rankPaths(
            candidates,
            edges,
            List.of(),
            List.of(dismissal(target.getId(), leadingBridge.getId())),
            temperatures,
            sourceHashes,
            10).getFirst();
        when(warmPathService.computePaths(eq(WORKSPACE_ID), eq(10), anyMap(), anyMap()))
            .thenReturn(List.of(before), List.of(after));

        var beforeSignal = detector.detectWarmPaths(WORKSPACE_ID, "before", AS_OF)
            .candidates().getFirst();
        var afterSignal = detector.detectWarmPaths(WORKSPACE_ID, "after", AS_OF)
            .candidates().getFirst();

        assertEquals(leadingBridge.getId(), before.getBridges().getFirst().getPersonId());
        assertEquals(remainingBridge.getId(), after.getBridges().getFirst().getPersonId());
        assertNotEquals(beforeSignal.getSourceStateHash(), afterSignal.getSourceStateHash());
    }

    @Test
    void warmPathSourceStateTracksPersistedTouchesAndGraphEvidenceInsteadOfScores() {
        IntroCandidatePerson bridge = introCandidate(22, "Bridge");
        IntroCandidatePerson target = introCandidate(23, "Target");
        PersonEdge edge = edge(31, bridge.getId(), target.getId(), 2);
        Map<Integer, String> sourceHashes = Map.of(
            bridge.getId(), "a".repeat(64),
            target.getId(), "b".repeat(64));

        WarmPathDto before = WarmPathService.rankPaths(
            List.of(bridge, target),
            List.of(edge),
            List.of(),
            List.of(),
            Map.of(bridge.getId(), warmTemperature(bridge.getId(), 80)),
            sourceHashes,
            10).getFirst();
        WarmPathDto later = WarmPathService.rankPaths(
            List.of(bridge, target),
            List.of(edge),
            List.of(),
            List.of(),
            Map.of(bridge.getId(), warmTemperature(bridge.getId(), 70)),
            sourceHashes,
            10).getFirst();
        WarmPathDto changedEdge = WarmPathService.rankPaths(
            List.of(bridge, target),
            List.of(edge(31, bridge.getId(), target.getId(), 3)),
            List.of(),
            List.of(),
            Map.of(bridge.getId(), warmTemperature(bridge.getId(), 70)),
            sourceHashes,
            10).getFirst();

        assertNotEquals(before.getScore(), later.getScore());
        assertEquals(before.getSourceState(), later.getSourceState());
        assertNotEquals(later.getSourceState(), changedEdge.getSourceState());
    }

    private static RelationshipTemperatureDto temperature(int id, int daysUntilCold) {
        return new RelationshipTemperatureDto(
            id,
            60,
            "warm",
            "cooling",
            "2026-08-01 12:00:00",
            7,
            3,
            "2026-08-20",
            daysUntilCold,
            "warmth-v1",
            AS_OF);
    }

    private static RelationshipTemperatureDto warmTemperature(int id, int score) {
        return new RelationshipTemperatureDto(
            id,
            score,
            "warm",
            "stable",
            "2026-08-01 12:00:00",
            7,
            3,
            null,
            null,
            "warmth-v1",
            AS_OF);
    }

    private static IntroCandidatePerson introCandidate(int id, String name) {
        IntroCandidatePerson candidate = new IntroCandidatePerson();
        candidate.setId(id);
        candidate.setName(name);
        return candidate;
    }

    private static PersonEdge edge(int id, int personA, int personB, int strength) {
        PersonEdge edge = new PersonEdge();
        edge.setId(id);
        edge.setSourcePersonId(Math.min(personA, personB));
        edge.setTargetPersonId(Math.max(personA, personB));
        edge.setType("knows");
        edge.setStrength(strength);
        return edge;
    }

    private static WarmPathDismissal dismissal(int targetPersonId, int bridgePersonId) {
        WarmPathDismissal dismissal = new WarmPathDismissal();
        dismissal.setTargetPersonId(targetPersonId);
        dismissal.setBridgePersonId(bridgePersonId);
        return dismissal;
    }

    private static Activity activity(
            int id, Person person, String type, String timestamp) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setPerson(person);
        activity.setType(type);
        activity.setTimestamp(timestamp);
        return activity;
    }

    private static SourceScoringFixture sourceScoringFixture(
            List<Person> people, List<Activity> activities) {
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        when(personMapper.getProcessablePersons(WORKSPACE_ID)).thenReturn(people);
        when(personMapper.getByIds(eq(WORKSPACE_ID), anyList())).thenReturn(people);
        when(companyMapper.getRelationshipScoreAggregates(eq(WORKSPACE_ID), any(), any()))
            .thenReturn(List.of());
        when(companyMapper.getByIds(eq(WORKSPACE_ID), anyList())).thenReturn(List.of());
        when(activityMapper.getAllActivities(WORKSPACE_ID)).thenReturn(activities);
        when(noteMapper.getAllNotes(WORKSPACE_ID)).thenReturn(List.of());
        when(taskMapper.getAllTasks(WORKSPACE_ID)).thenReturn(List.of());
        return new SourceScoringFixture(
            personMapper,
            companyMapper,
            dealMapper,
            activityMapper,
            noteMapper,
            taskMapper);
    }

    private RelationshipSignalDetectorService detectorFor(
            ScoringService sourceScoringService,
            WarmPathService sourceWarmPathService,
            SourceScoringFixture fixture,
            Instant evaluationInstant) {
        return new RelationshipSignalDetectorService(
            sourceScoringService,
            dealRiskService,
            sourceWarmPathService,
            fixture.personMapper(),
            fixture.companyMapper(),
            fixture.dealMapper(),
            new ObjectMapper(),
            Clock.fixed(evaluationInstant, ZoneOffset.UTC));
    }

    private static WarmPathService warmPathService(
            IntroductionMapper introductionMapper,
            PersonEdgeReadService edgeReader,
            PersonMapper personMapper,
            ScoringService sourceScoringService,
            Instant evaluationInstant) {
        return new WarmPathService(
            introductionMapper,
            edgeReader,
            personMapper,
            sourceScoringService,
            mock(WorkspaceService.class),
            mock(AuthService.class),
            mock(TaskService.class),
            Clock.fixed(evaluationInstant, ZoneOffset.UTC));
    }

    private record SourceScoringFixture(
        PersonMapper personMapper,
        CompanyMapper companyMapper,
        DealMapper dealMapper,
        ActivityMapper activityMapper,
        NoteMapper noteMapper,
        TaskMapper taskMapper
    ) {
        private ScoringService serviceAt(Instant evaluationInstant) {
            return new ScoringService(
                personMapper,
                companyMapper,
                dealMapper,
                activityMapper,
                noteMapper,
                taskMapper,
                Clock.fixed(evaluationInstant, ZoneOffset.UTC));
        }
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        person.setName("Person " + id);
        return person;
    }

    private static Deal deal(int id) {
        Deal deal = new Deal();
        deal.setId(id);
        deal.setName("Deal " + id);
        return deal;
    }
}

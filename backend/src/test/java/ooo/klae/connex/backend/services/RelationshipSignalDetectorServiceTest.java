package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmPathBridgeDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

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
        when(warmPathService.computePaths(WORKSPACE_ID, 10)).thenReturn(List.of(path));

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
        verify(warmPathService).computePaths(WORKSPACE_ID, 10);
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
            paths.add(path);
        }
        when(warmPathService.computePaths(WORKSPACE_ID, 10)).thenReturn(paths);

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
    void materialPresentedEvidenceChangesDismissalFingerprints() {
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
        when(warmPathService.computePaths(WORKSPACE_ID, 10)).thenReturn(List.of(path));
        String pathBefore = detector.detectWarmPaths(WORKSPACE_ID, "before", AS_OF)
            .candidates().getFirst().getSourceStateHash();
        bridge.setOverlapEndYear(2021);
        bridge.setScore(75);
        path.setScore(75);
        String pathAfter = detector.detectWarmPaths(WORKSPACE_ID, "after", AS_OF)
            .candidates().getFirst().getSourceStateHash();

        assertNotEquals(decayBefore, decayAfter);
        assertNotEquals(pathBefore, pathAfter);
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

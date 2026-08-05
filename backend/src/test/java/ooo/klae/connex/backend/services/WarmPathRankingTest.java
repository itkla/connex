package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmPathBridgeDto;
import ooo.klae.connex.backend.dto.WarmPathDto;

/**
 * Unit tests for the pure warm-path ranking (issue #614), exercised without a database: evidence
 * tiers, bridge and target gates, dismissals, fatigue caps, and the multiplicative ordering.
 */
class WarmPathRankingTest {

    @Test
    void verifiedEdgeSurfacesDirectionalPathOnly() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 200, "Globex"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 5, "cold", 90, 3));

        List<WarmPathDto> rows = rank(candidates, List.of(edge(1, 2, 2)), List.of(), List.of(), temps);

        assertEquals(1, rows.size());
        WarmPathDto row = rows.get(0);
        assertEquals(2, row.getTargetId());
        assertEquals(WarmPathService.REACH_REWARM, row.getReachType());
        assertEquals(1, row.getBridges().size());
        WarmPathBridgeDto bridge = row.getBridges().get(0);
        assertEquals(1, bridge.getPersonId());
        assertEquals(WarmPathService.EVIDENCE_CONNECTION, bridge.getEvidenceType());
        assertEquals(54, bridge.getScore());
        assertEquals(row.getScore(), bridge.getScore());
        assertEquals(List.of(1, 2), bridge.getSupportingPersonIds());
        assertEquals(List.of(102), bridge.getSupportingEdgeIds());
    }

    @Test
    void neverEngagedImportSurfacesAsReach() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Imported", 200, "Globex"),
            person(3, "Unknown", 300, "Initrode"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 0, "cold", null, 0));

        List<WarmPathDto> rows = rank(
            candidates, List.of(edge(1, 2, 2), edge(1, 3, 2)), List.of(), List.of(), temps);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> WarmPathService.REACH_NEW.equals(row.getReachType())),
            "a never-touched import and a contact absent from the warmth map are both reach targets");
    }

    @Test
    void sameCurrentEmployerSurfacesAsColleagueTier() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 100, "Acme"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 5, "cold", 90, 3));

        List<WarmPathDto> rows = rank(candidates, List.of(), List.of(), List.of(), temps);

        assertEquals(1, rows.size());
        WarmPathBridgeDto bridge = rows.get(0).getBridges().get(0);
        assertEquals(WarmPathService.EVIDENCE_COLLEAGUES, bridge.getEvidenceType());
        assertEquals("Acme", bridge.getEvidenceCompany());
        assertEquals(43, bridge.getScore());
    }

    @Test
    void datedTenureOverlapSurfacesAsFormerColleagues() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 200, "Globex"));
        List<IntroEmploymentRow> employment = List.of(
            stint(1, 900, "Hooli", "2018-01-01 00:00:00", "2021-06-01 00:00:00"),
            stint(2, 900, "Hooli", "2019-03-01 00:00:00", "2022-01-01 00:00:00"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 5, "cold", 90, 3));

        List<WarmPathDto> rows = rank(candidates, List.of(), employment, List.of(), temps);

        assertEquals(1, rows.size());
        WarmPathBridgeDto bridge = rows.get(0).getBridges().get(0);
        assertEquals(WarmPathService.EVIDENCE_FORMER_COLLEAGUES, bridge.getEvidenceType());
        assertEquals("Hooli", bridge.getEvidenceCompany());
        assertEquals(2019, bridge.getOverlapStartYear());
        assertEquals(2021, bridge.getOverlapEndYear());
        assertEquals(26, bridge.getScore());
    }

    @Test
    void stintChurnStaysBoundedAndStillFindsTheOverlap() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 200, "Globex"));
        List<IntroEmploymentRow> employment = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            employment.add(stint(1, 900, "Hooli",
                "2019-01-01 00:00:0" + (i % 10), "2020-01-01 00:00:00"));
        }
        employment.add(stint(2, 900, "Hooli", "2019-06-01 00:00:00", "2021-01-01 00:00:00"));

        List<WarmPathDto> rows = rank(candidates, List.of(), employment, List.of(), warmAndCold());

        assertEquals(1, rows.size(), "capped stints must still yield the overlap evidence");
        assertEquals(WarmPathService.EVIDENCE_FORMER_COLLEAGUES,
            rows.get(0).getBridges().get(0).getEvidenceType());
    }

    @Test
    void disjointTenuresAtTheSameEmployerDoNotTie() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 200, "Globex"));
        List<IntroEmploymentRow> employment = List.of(
            stint(1, 900, "Hooli", "2010-01-01 00:00:00", "2012-01-01 00:00:00"),
            stint(2, 900, "Hooli", "2020-01-01 00:00:00", "2022-01-01 00:00:00"));

        List<WarmPathDto> rows = rank(candidates, List.of(), employment, List.of(), warmAndCold());

        assertTrue(rows.isEmpty(),
            "a 2010 and a 2020 employee of the same company do not know each other");
    }

    @Test
    void currentColleaguesNeverDowngradeToFormerColleagues() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 100, "Acme"));
        List<IntroEmploymentRow> employment = List.of(
            stint(1, 100, "Acme", "2020-01-01 00:00:00", null),
            stint(2, 100, "Acme", "2021-01-01 00:00:00", null));

        List<WarmPathDto> rows = rank(candidates, List.of(), employment, List.of(), warmAndCold());

        assertEquals(1, rows.size());
        assertEquals(WarmPathService.EVIDENCE_COLLEAGUES,
            rows.get(0).getBridges().get(0).getEvidenceType());
    }

    @Test
    void verifiedEdgeOutranksCoexistingInferredTie() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Target", 100, "Acme"));

        List<WarmPathDto> rows = rank(candidates, List.of(edge(1, 2, 3)), List.of(), List.of(), warmAndCold());

        assertEquals(WarmPathService.EVIDENCE_CONNECTION,
            rows.get(0).getBridges().get(0).getEvidenceType());
    }

    @Test
    void warmOrRecentlyTouchedTargetsAreExcluded() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "StillWarm", 200, "Globex"),
            person(3, "JustTouched", 300, "Initrode"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 40, "warm", 10, 4));
        temps.put(3, temp(3, 20, "cool", 10, 2));

        List<WarmPathDto> rows = rank(
            candidates, List.of(edge(1, 2, 2), edge(1, 3, 2)), List.of(), List.of(), temps);

        assertTrue(rows.isEmpty(),
            "a warm relationship and a cool one touched days ago are not reach targets");
    }

    @Test
    void quietWarmContactIsNotMistakenForNeverEngaged() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "QuietButWarm", 200, "Globex"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 43, "warm", 25, 0));

        List<WarmPathDto> rows = rank(candidates, List.of(edge(1, 2, 2)), List.of(), List.of(), temps);

        assertTrue(rows.isEmpty(),
            "a warm contact whose touches merely aged out of the recent window is not a reach target");
    }

    @Test
    void staleTargetStillSurfacesWithCurrentEvaluationEvidence() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Stale target", 200, "Globex"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 0, "cold", 365, 1));

        List<WarmPathDto> rows = rank(
            candidates, List.of(edge(1, 2, 2)), List.of(), List.of(), temps);

        assertEquals(1, rows.size());
        assertEquals(365, rows.get(0).getTargetDaysSinceTouch());
        assertEquals(WarmPathService.REACH_REWARM, rows.get(0).getReachType());
    }

    @Test
    void coolBridgeIsExcluded() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "CoolBridge", 100, "Acme"),
            person(2, "Target", 200, "Globex"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 20, "cool", 20, 2));
        temps.put(2, temp(2, 5, "cold", 90, 3));

        List<WarmPathDto> rows = rank(candidates, List.of(edge(1, 2, 2)), List.of(), List.of(), temps);

        assertTrue(rows.isEmpty(), "the team lacks the standing to ask a cool contact for an intro");
    }

    @Test
    void wholeTargetDismissalHidesTheRowAndAvenueDismissalOnlyThatBridge() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "BridgeA", 100, "Acme"),
            person(2, "BridgeB", 200, "Globex"),
            person(3, "Target", 300, "Initrode"));
        List<PersonEdge> edges = List.of(edge(1, 3, 2), edge(2, 3, 2));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 60, "hot", 5, 8));
        temps.put(3, temp(3, 5, "cold", 90, 3));

        List<WarmPathDto> avenueDismissed = rank(
            candidates, edges, List.of(), List.of(dismissal(3, 1)), temps);
        assertEquals(1, avenueDismissed.size());
        assertEquals(1, avenueDismissed.get(0).getBridges().size());
        assertEquals(2, avenueDismissed.get(0).getBridges().get(0).getPersonId());

        List<WarmPathDto> targetDismissed = rank(
            candidates, edges, List.of(), List.of(dismissal(3, null)), temps);
        assertTrue(targetDismissed.isEmpty());
    }

    @Test
    void oversizedCompanyGroupsCarryNoColleagueSignal() {
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        for (int id = 1; id <= 41; id++) {
            candidates.add(person(id, "Person " + id, 100, "MegaCorp"));
            temps.put(id, id == 1 ? temp(id, 60, "hot", 5, 8) : temp(id, 5, "cold", 90, 3));
        }

        List<WarmPathDto> rows = rank(candidates, List.of(), List.of(), List.of(), temps);

        assertTrue(rows.isEmpty(), "colleague at a 41-person company is not a usable tie");
    }

    @Test
    void bridgesPerTargetAreCappedAtThree() {
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        List<PersonEdge> edges = new ArrayList<>();
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        candidates.add(person(9, "Target", 900, "Globex"));
        temps.put(9, temp(9, 5, "cold", 90, 3));
        for (int id = 1; id <= 4; id++) {
            candidates.add(person(id, "Bridge " + id, id * 100, "Co " + id));
            temps.put(id, temp(id, 40 + id * 5, "warm", 5, 8));
            edges.add(edge(id, 9, 2));
        }

        List<WarmPathDto> rows = rank(candidates, edges, List.of(), List.of(), temps);

        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).getBridges().size());
        assertEquals(4, rows.get(0).getBridges().get(0).getPersonId(),
            "the warmest bridge leads the avenue list");
    }

    @Test
    void aSingleBridgeAppearsInAtMostThreeRows() {
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        List<PersonEdge> edges = new ArrayList<>();
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        candidates.add(person(1, "Bridge", 100, "Acme"));
        temps.put(1, temp(1, 60, "hot", 5, 8));
        for (int id = 2; id <= 5; id++) {
            candidates.add(person(id, "Target " + id, id * 100, "Co " + id));
            temps.put(id, temp(id, 5, "cold", 90, 3));
            edges.add(edge(1, id, 2));
        }

        List<WarmPathDto> rows = rank(candidates, edges, List.of(), List.of(), temps);

        assertEquals(3, rows.size(), "bridge fatigue caps one contact at three rows");
    }

    @Test
    void verifiedWarmBridgeOutranksHotBridgeOnWeakInference() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "VerifiedWarm", 100, "Acme"),
            person(2, "HotFormer", 200, "Globex"),
            person(3, "Target", 300, "Initrode"));
        List<IntroEmploymentRow> employment = List.of(
            stint(2, 900, "Hooli", "2019-01-01 00:00:00", "2021-01-01 00:00:00"),
            stint(3, 900, "Hooli", "2019-01-01 00:00:00", "2021-01-01 00:00:00"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 40, "warm", 5, 8));
        temps.put(2, temp(2, 80, "hot", 5, 8));
        temps.put(3, temp(3, 0, "cold", 90, 3));

        List<WarmPathDto> rows = rank(candidates, List.of(edge(1, 3, 2)), employment, List.of(), temps);

        assertEquals(1, rows.size());
        List<WarmPathBridgeDto> bridges = rows.get(0).getBridges();
        assertEquals(2, bridges.size());
        assertEquals(1, bridges.get(0).getPersonId(),
            "a verified edge from a warm contact beats a weak inference from a hot one");
        assertEquals(38, bridges.get(0).getScore());
        assertEquals(36, bridges.get(1).getScore());
    }

    @Test
    void rewarmOutranksReachOnEqualScore() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Bridge", 100, "Acme"),
            person(2, "Dormant", 200, "Globex"),
            person(3, "NeverMet", 300, "Initrode"));
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 0, "cold", 200, 1));
        temps.put(3, temp(3, 0, "cold", null, 0));

        List<WarmPathDto> rows = rank(
            candidates, List.of(edge(1, 2, 2), edge(1, 3, 2)), List.of(), List.of(), temps);

        assertEquals(2, rows.size());
        assertEquals(rows.get(0).getScore(), rows.get(1).getScore());
        assertEquals(WarmPathService.REACH_REWARM, rows.get(0).getReachType());
        assertEquals(WarmPathService.REACH_NEW, rows.get(1).getReachType());
    }

    @Test
    void limitBoundsTheRowCount() {
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        List<PersonEdge> edges = new ArrayList<>();
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        for (int bridgeId = 1; bridgeId <= 3; bridgeId++) {
            candidates.add(person(bridgeId, "Bridge " + bridgeId, bridgeId * 100, "Co " + bridgeId));
            temps.put(bridgeId, temp(bridgeId, 60, "hot", 5, 8));
        }
        for (int targetId = 10; targetId < 16; targetId++) {
            candidates.add(person(targetId, "Target " + targetId, targetId * 100, "Co " + targetId));
            temps.put(targetId, temp(targetId, 5, "cold", 90, 3));
            edges.add(edge(1 + targetId % 3, targetId, 2));
        }

        List<WarmPathDto> rows = WarmPathService.rankPaths(
            candidates, edges, List.of(), List.of(), temps, 4);

        assertEquals(4, rows.size());
    }

    @Test
    void emptyWithoutEligibleCandidates() {
        assertTrue(rank(List.of(), List.of(), List.of(), List.of(), Map.of()).isEmpty());
        assertTrue(rank(List.of(person(1, "Solo", 100, "Acme")),
            List.of(), List.of(), List.of(), Map.of()).isEmpty());
    }

    private static List<WarmPathDto> rank(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment,
            List<WarmPathDismissal> dismissals,
            Map<Integer, RelationshipTemperatureDto> temperatures) {
        return WarmPathService.rankPaths(candidates, edges, employment, dismissals, temperatures, 50);
    }

    private static Map<Integer, RelationshipTemperatureDto> warmAndCold() {
        Map<Integer, RelationshipTemperatureDto> temps = new HashMap<>();
        temps.put(1, temp(1, 60, "hot", 5, 8));
        temps.put(2, temp(2, 5, "cold", 90, 3));
        return temps;
    }

    private static IntroCandidatePerson person(int id, String name, Integer companyId, String companyName) {
        IntroCandidatePerson person = new IntroCandidatePerson();
        person.setId(id);
        person.setName(name);
        person.setTitle("Title " + id);
        person.setCompanyId(companyId);
        person.setCompanyName(companyName);
        return person;
    }

    private static PersonEdge edge(int a, int b, int strength) {
        PersonEdge edge = new PersonEdge();
        edge.setId(Math.min(a, b) * 100 + Math.max(a, b));
        edge.setWorkspaceId(1);
        edge.setSourcePersonId(Math.min(a, b));
        edge.setTargetPersonId(Math.max(a, b));
        edge.setType("knows");
        edge.setStrength(strength);
        return edge;
    }

    private static IntroEmploymentRow stint(
            int personId, Integer companyId, String companyName, String startedAt, String endedAt) {
        IntroEmploymentRow row = new IntroEmploymentRow();
        row.setPersonId(personId);
        row.setCompanyId(companyId);
        row.setCompanyName(companyName);
        row.setStartedAt(startedAt);
        row.setEndedAt(endedAt);
        return row;
    }

    private static WarmPathDismissal dismissal(int targetId, Integer bridgeId) {
        WarmPathDismissal dismissal = new WarmPathDismissal();
        dismissal.setTargetPersonId(targetId);
        dismissal.setBridgePersonId(bridgeId);
        return dismissal;
    }

    private static RelationshipTemperatureDto temp(
            int id, int score, String band, Integer daysSinceTouch, int touchCount) {
        return new RelationshipTemperatureDto(
            id, score, band, "steady", null, daysSinceTouch, touchCount, null, null,
            "test-model", Instant.EPOCH);
    }
}

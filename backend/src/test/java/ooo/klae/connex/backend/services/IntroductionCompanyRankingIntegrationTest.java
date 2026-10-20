package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Compares report ranking, scores, and tie ordering using original and converted SQL inputs. */
class IntroductionCompanyRankingIntegrationTest {
    @Test
    void companyMaskingPreservesReportRankingScoresAndTieBreaks() throws Exception {
        try (var fixture = new RecordVisibilityPlaneFixture(Records.INTRODUCTIONS, false)) {
            fixture.installTenant();
            JdbcTemplate jdbc = fixture.controlJdbc;
            assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM person WHERE workspace_id = ?",
                Integer.class, fixture.workspaceId));
            List<IntroCandidatePerson> original = fixture.legacy.selectList(
                "IntroductionMapper.findCandidatePersonsForReport", fixture.parameters());
            List<IntroCandidatePerson> converted = fixture.introductionMapper.findCandidatePersonsForReport(
                fixture.workspaceId, fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson(), 100);
            List<PersonEdge> edges = new ArrayList<>();
            Map<Integer, RelationshipTemperatureDto> scores = new HashMap<>();
            for (int i = 0; i < fixture.personIds.size(); i++) {
                int personId = fixture.personIds.get(i);
                PersonEdge edge = new PersonEdge();
                edge.setId(i + 1);
                edge.setSourcePersonId(Integer.MAX_VALUE);
                edge.setTargetPersonId(personId);
                edges.add(edge);
                RelationshipTemperatureDto score = new RelationshipTemperatureDto(
                    personId, 60, "warm", "steady", null, null, 0, null, null, "test-model", Instant.EPOCH);
                scores.put(personId, score);
            }
            var expected = ReportNetworkService.rankReverseIntroSuggestions(original, edges, List.of(), List.of(), scores);
            var actual = ReportNetworkService.rankReverseIntroSuggestions(converted, edges, List.of(), List.of(), scores);
            assertFalse(expected.isEmpty());
            assertEquals(expected, actual);
            assertEquals(21, actual.size());
        }
    }
}

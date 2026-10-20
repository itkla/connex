package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;

/** Pins employer masking, lifecycle parity, and candidate bounds against the original statements. */
class IntroductionCompanyVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(true, false);
        controlJdbc = fixture.controlJdbc;
        fixture.installTenant();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void candidatePersonsMaskEmployersAndKeepCandidates() {
        List<IntroCandidatePerson> candidates = fixture.introductionMapper.findCandidatePersons(
            fixture.workspaceId, scope());
        assertMatrix(candidates);
        assertEquals(sorted(fixture.legacy.selectList("IntroductionMapper.findCandidatePersons", fixture.parameters())),
            sorted(candidates));
    }

    @Test
    void reportCandidatesPreserveEmployerMaskingIdOrderAndLimit() {
        var parameters = fixture.parameters();
        for (int limit : List.of(0, 1, 3, 7, 100)) {
            parameters.put("limit", limit);
            List<IntroCandidatePerson> actual = fixture.introductionMapper.findCandidatePersonsForReport(
                fixture.workspaceId, scope(), limit);
            assertEquals(fixture.personIds.stream().limit(limit).toList(), actual.stream()
                .map(IntroCandidatePerson::getId).toList());
            assertEquals(fixture.legacy.selectList("IntroductionMapper.findCandidatePersonsForReport", parameters), actual);
        }
        assertNull(fixture.introductionMapper.findCandidatePersonsForReport(fixture.workspaceId, scope(), 3)
            .get(2).getCompanyId());
    }

    @Test
    void warmPathCandidatesMaskEmployersWithoutRequiringEngagement() {
        controlJdbc.update("DELETE FROM note WHERE workspace_id = ?", fixture.workspaceId);
        assertTrue(fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope()).isEmpty());
        List<IntroCandidatePerson> candidates = fixture.introductionMapper.findWarmPathCandidates(
            fixture.workspaceId, scope());
        assertMatrix(candidates);
        assertEquals(sorted(fixture.legacy.selectList("IntroductionMapper.findWarmPathCandidates", fixture.parameters())),
            sorted(candidates));
    }

    @Test
    void inactiveWorkspacesAndOrganizationsKeepLegacyEmployerVisibility() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        assertMatrix(fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope()));
        assertMatrix(fixture.introductionMapper.findCandidatePersonsForReport(fixture.workspaceId, scope(), 100));
        assertMatrix(fixture.introductionMapper.findWarmPathCandidates(fixture.workspaceId, scope()));
        assertEquals(4, fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIds().size());
    }

    @Test
    void personEligibilityRemainsIndependentOfEmployerMasking() {
        controlJdbc.update("UPDATE person SET intro_excluded = TRUE WHERE id = ?", fixture.personIds.get(0));
        controlJdbc.update("UPDATE person SET suspended_at = NOW() WHERE id = ?", fixture.personIds.get(1));
        controlJdbc.update("UPDATE person SET archived_at = NOW() WHERE id = ?", fixture.personIds.get(2));
        controlJdbc.update("UPDATE person SET provision_ceased_at = NOW() WHERE id = ?", fixture.personIds.get(3));
        List<IntroCandidatePerson> actual = fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope());
        assertEquals(fixture.personIds.subList(3, 7), sorted(actual).stream().map(IntroCandidatePerson::getId).toList());
        assertEquals(sorted(fixture.legacy.selectList("IntroductionMapper.findCandidatePersons", fixture.parameters())), sorted(actual));
    }

    private String scope() {
        return fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
    }

    private void assertMatrix(List<IntroCandidatePerson> candidates) {
        List<IntroCandidatePerson> ordered = sorted(candidates);
        assertEquals(fixture.personIds, ordered.stream().map(IntroCandidatePerson::getId).toList());
        assertEquals(Arrays.asList("Employer 0", "Employer 1", null, null, "Employer 4", null, null),
            ordered.stream().map(IntroCandidatePerson::getCompanyName).toList());
        assertEquals(Arrays.asList(fixture.companyIds.get(0), fixture.companyIds.get(1), null, null,
            fixture.companyIds.get(4), null, null), ordered.stream().map(IntroCandidatePerson::getCompanyId).toList());
    }

    private static List<IntroCandidatePerson> sorted(List<IntroCandidatePerson> people) {
        return people.stream().sorted(Comparator.comparingInt(IntroCandidatePerson::getId)).toList();
    }
}

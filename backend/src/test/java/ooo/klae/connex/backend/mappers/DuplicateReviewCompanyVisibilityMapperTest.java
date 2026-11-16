package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.DuplicateReviewItemRow;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Verifies optional employer joins never change the decision set, counts, ordering, or pagination. */
class DuplicateReviewCompanyVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.DUPLICATE_REVIEW, false);
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
    void visibleItemsMaskBothEmployerSidesWithoutChangingDecisionVisibility() {
        assertVisibleItemMatrix();
    }

    private void assertVisibleItemMatrix() {
        List<DuplicateReviewItemRow> rows = rows(100, 0);
        assertEquals(9, rows.size());
        assertEquals(fixture.legacy.selectList("DuplicateReviewMapper.findVisibleItems", fixture.parameters()), rows);
        List<String> labels = Arrays.asList("Employer 0", "Employer 1", null, null, "Employer 4", null, null);
        for (int i = 0; i < fixture.pairIds.size(); i++) {
            long id = fixture.pairIds.get(i);
            var row = rows.stream().filter(item -> item.getId() == id).findFirst().orElseThrow();
            assertEquals(labels.get(i), row.getLowCompanyName());
            assertEquals(labels.get(i), row.getHighCompanyName());
        }
    }

    @Test
    void visibleItemByIdPreservesEmployerMaskingAcrossDecisionStates() {
        for (long id : fixture.pairIds) {
            var parameters = fixture.parameters();
            parameters.put("id", id);
            var before = fixture.duplicateReviewMapper.findVisibleItemById(fixture.workspaceId, scope(), id, 20);
            assertEquals(List.of(before), fixture.legacy.selectList("DuplicateReviewMapper.findVisibleItemById", parameters));
            fixture.duplicateReviewMapper.dismiss(fixture.workspaceId, id, fixture.userId, "reviewed",
                LocalDateTime.of(2026, 9, 2, 12, 0));
            var dismissed = fixture.duplicateReviewMapper.findVisibleItemById(fixture.workspaceId, scope(), id, 20);
            assertEquals("dismissed", dismissed.getState());
            assertEquals(before.getLowCompanyName(), dismissed.getLowCompanyName());
            assertEquals(before.getHighCompanyName(), dismissed.getHighCompanyName());
            assertEquals(List.of(dismissed), fixture.legacy.selectList("DuplicateReviewMapper.findVisibleItemById", parameters));
            assertEquals(1, fixture.duplicateReviewMapper.reopen(fixture.workspaceId, id));
            assertEquals("open", fixture.duplicateReviewMapper.findVisibleItemById(fixture.workspaceId, scope(), id, 20).getState());
        }
    }

    @Test
    void countMatchesLegacyLabelJoinedCountAcrossFilters() {
        assertCountParity();
    }

    private void assertCountParity() {
        var parameters = fixture.parameters();
        for (String type : Arrays.asList(null, "person", "company")) {
            for (String kind : Arrays.asList(null, "email", "domain")) {
                for (String state : Arrays.asList(null, "open", "dismissed")) {
                    parameters.put("recordType", type);
                    parameters.put("kind", kind);
                    parameters.put("state", state);
                    long count = fixture.duplicateReviewMapper.countVisibleItems(fixture.workspaceId, type, kind, state, 20);
                    assertEquals(fixture.legacy.count("DuplicateReviewMapper.countVisibleItems", parameters), count);
                    assertEquals(fixture.duplicateReviewMapper.findVisibleItems(
                        fixture.workspaceId, scope(), type, kind, state, 100, 0, 20).size(), count);
                }
            }
        }
        assertEquals(9, fixture.duplicateReviewMapper.countVisibleItems(fixture.workspaceId, null, null, null, 20));
        assertEquals(7, fixture.duplicateReviewMapper.countVisibleItems(fixture.workspaceId, "person", null, "open", 20));
        assertEquals(1, fixture.duplicateReviewMapper.countVisibleItems(fixture.workspaceId, "company", null, "open", 20));
    }

    @Test
    void visibleItemsPreserveDetectedAtAndIdOrderingLimitsAndOffsets() {
        List<Long> expected = rows(100, 0).stream().map(DuplicateReviewItemRow::getId).toList();
        assertEquals(expected.stream().sorted(java.util.Comparator.reverseOrder()).toList(), expected);
        for (int limit : List.of(0, 1, 3, 20)) {
            for (long offset : List.of(0L, 2L, 9L)) {
                var parameters = fixture.parameters();
                parameters.put("limit", limit);
                parameters.put("offset", offset);
                List<DuplicateReviewItemRow> actual = rows(limit, offset);
                assertEquals(expected.stream().skip(offset).limit(limit).toList(), actual.stream().map(DuplicateReviewItemRow::getId).toList());
                assertEquals(fixture.legacy.selectList("DuplicateReviewMapper.findVisibleItems", parameters), actual);
            }
        }
    }

    @Test
    void inactiveWorkspacesKeepLegacyEmployerLabels() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        assertVisibleItemMatrix();
        assertCountParity();
    }

    @Test
    void ineligibleEvidenceAndRecordsStillExcludeReviewItems() {
        controlJdbc.update("UPDATE person_identity SET superseded_at = NOW() WHERE person_id = ?", fixture.personIds.get(0));
        controlJdbc.update("UPDATE person SET provision_ceased_at = NOW() WHERE id = ?", fixture.personIds.get(4));
        controlJdbc.update("UPDATE company SET archived_at = NOW() WHERE id = ?", fixture.companyIds.getFirst());
        assertEquals(5, fixture.duplicateReviewMapper.countVisibleItems(fixture.workspaceId, null, null, null, 20));
        assertEquals(fixture.legacy.count("DuplicateReviewMapper.countVisibleItems", fixture.parameters()), rows(100, 0).size());
    }

    private String scope() {
        return fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
    }

    private List<DuplicateReviewItemRow> rows(int limit, long offset) {
        return fixture.duplicateReviewMapper.findVisibleItems(fixture.workspaceId, scope(), null, null, null, limit, offset, 20);
    }
}

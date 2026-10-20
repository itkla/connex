package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Pins all Note visibility ceilings, labels, counts, and bounds against the frozen original SQL. */
class NoteVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.NOTES, false);
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
    void linkedNamesCannotRevealUnsharedSiblingOrForgedForeignShares() {
        for (int i = 0; i < fixture.personIds.size(); i++) {
            String query = "%LinkedNeedle " + i + "%";
            boolean nameVisible = List.of(0, 1, 4, 6, 7).contains(i);
            List<Integer> expected = nameVisible
                ? List.of(fixture.privateNoteIds.get(i), fixture.noteIds.get(i)) : List.of();
            assertEquals(expected, ids(visiblePage(query, List.of(), "created", "desc", 100, 0)), "person " + i);
            assertEquals(nameVisible ? List.of(fixture.noteIds.get(i)) : List.of(),
                ids(workspacePage(query, List.of(), "created", "desc", 100, 0)), "workspace person " + i);
            assertEquals(expected.size(), visibleCount(query, List.of()));
            assertEquals(nameVisible ? 1 : 0, workspaceCount(query, List.of()));
            assertEquals(expected, ids(search(query, 100, 0)), "search person " + i);
            assertPageParity(query, List.of(), "created", "desc", 100, 0);
            assertSearchParity(query, 100, 0);
        }
    }

    @Test
    void searchMasksInvisiblePeopleWithoutDiscardingMatchingNoteBodies() {
        List<Note> actual = search("%body%", 100, 0);
        assertEquals(16, actual.size());
        for (Note note : actual) {
            int personCase = fixture.personIds.indexOf(note.getPerson().getId());
            if (List.of(2, 3, 5).contains(personCase)) {
                assertNull(note.getPerson().getName());
            } else {
                assertEquals("LinkedNeedle " + personCase, note.getPerson().getName());
            }
        }
        assertSearchParity("%body%", 100, 0);
        assertSearchParity("%body%", 3, 2);
        assertSearchParity("%body%", 0, 0);
    }

    @Test
    void pageCountsOrderingAndOffsetsKeepTheOriginalAuthorListBinding() {
        for (String query : new String[] {null, "%LinkedNeedle%", "%Public%", "%missing%"}) {
            List<Integer> authors = "%missing%".equals(query) ? List.of(fixture.userId) : List.of();
            for (String sort : List.of("updated", "created", "title")) {
                for (String direction : List.of("asc", "desc")) {
                    assertPageParity(query, authors, sort, direction, 0, 0);
                    assertPageParity(query, authors, sort, direction, 3, 1);
                    assertPageParity(query, authors, sort, direction, 100, 50);
                }
            }
        }
        assertEquals(16, visibleCount(null, List.of()));
        assertEquals(8, workspaceCount(null, List.of()));
        assertEquals(16, visibleCount("%missing%", List.of(fixture.userId)));
        assertEquals(8, workspaceCount("%missing%", List.of(fixture.userId)));
    }

    @Test
    void privateEvidenceCountsRequireVisiblePeopleAndCompaniesAcrossShares() {
        for (int i = 0; i < fixture.personIds.size(); i++) {
            int expected = List.of(0, 1, 4, 5, 6).contains(i) ? 1 : 0;
            int person = fixture.personIds.get(i);
            assertEquals(expected, personCount(person, reference(), 100));
            assertPersonCountParity(person, reference(), 100);
            assertEquals(0, personCount(person, reference(), 0));
        }
        for (int i = 0; i < fixture.companyIds.size(); i++) {
            int expected = switch (i) {
                case 0 -> 2;
                case 1, 4, 5 -> 1;
                default -> 0;
            };
            int company = fixture.companyIds.get(i);
            assertEquals(expected, companyCount(company, reference(), 100));
            assertCompanyCountParity(company, reference(), 100);
            assertEquals(Math.min(expected, 1), companyCount(company, reference(), 1));
            assertCompanyCountParity(company, reference(), 1);
        }
    }

    @Test
    void companyDealAttributionRequiresVisiblePersonEvidenceAndVisibleTargetCompany() throws Exception {
        controlJdbc.update("DELETE FROM note WHERE workspace_id = ?", fixture.workspaceId);
        for (int i = 0; i < fixture.personIds.size(); i++) {
            fixture.addNote(fixture.workspaceId, fixture.userId, fixture.personIds.get(i), fixture.dealIds.getFirst(),
                "private", "Deal person " + i, "evidence", "2026-09-20 12:00:00");
        }
        fixture.addNote(fixture.workspaceId, fixture.userId, null, fixture.dealIds.getFirst(),
            "private", "Deal only", "evidence", "2026-09-20 12:00:00");
        fixture.addNote(fixture.workspaceId, fixture.otherUserId, null, fixture.dealIds.getFirst(),
            "private", "Colleague deal", "evidence", "2026-09-20 12:00:00");
        assertEquals(6, companyCount(fixture.companyIds.getFirst(), reference(), 100));
        assertCompanyCountParity(fixture.companyIds.getFirst(), reference(), 100);
        for (int i : List.of(1, 2, 3, 4, 5)) {
            fixture.addNote(fixture.workspaceId, fixture.userId, null, fixture.dealIds.get(i),
                "private", "Target company " + i, "evidence", "2026-09-20 12:00:00");
            assertCompanyCountParity(fixture.companyIds.get(i), reference(), 100);
        }
        assertEquals(0, companyCount(fixture.companyIds.get(2), reference(), 100));
        assertEquals(0, companyCount(fixture.companyIds.get(3), reference(), 100));
    }

    @Test
    void lifecycleAndPersonRestrictionsRetainTheirDifferentLegacyPredicates() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        assertTrue(fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIds()
            .contains(fixture.workspaceIds.get(4)));
        assertPageParity("%LinkedNeedle%", List.of(), "created", "desc", 100, 0);
        assertSearchParity("%LinkedNeedle%", 100, 0);
        assertEquals(2, visibleCount("%LinkedNeedle 4%", List.of()));
        assertEquals(0, visibleCount("%LinkedNeedle 5%", List.of()));
        assertEquals(1, personCount(fixture.personIds.get(5), reference(), 100));
        assertEquals(1, personCount(fixture.personIds.get(6), reference(), 100));
        assertEquals(0, personCount(fixture.personIds.get(7), reference(), 100));
        for (int person : fixture.personIds) {
            assertPersonCountParity(person, reference(), 100);
        }
        for (int company : fixture.companyIds) {
            assertCompanyCountParity(company, reference(), 100);
        }
    }

    @Test
    void evidenceCutoffAndSourceLimitAreAppliedInSql() throws Exception {
        int person = fixture.personIds.get(1);
        for (int i = 0; i < 3; i++) {
            fixture.addNote(fixture.workspaceId, fixture.userId, person, null, "private", "More evidence", "evidence",
                "2026-09-20 12:00:00");
        }
        assertEquals(4, personCount(person, reference(), 100));
        assertEquals(2, personCount(person, reference(), 2));
        assertEquals(1, personCount(person, LocalDateTime.of(2026, 9, 2, 12, 0), 100));
        assertEquals(0, personCount(person, LocalDateTime.of(2026, 9, 1, 12, 0), 100));
        assertPersonCountParity(person, reference(), 2);
        assertPersonCountParity(person, LocalDateTime.of(2026, 9, 2, 12, 0), 100);
        assertCompanyCountParity(fixture.companyIds.get(1), reference(), 2);
    }

    private void assertPageParity(String query, List<Integer> authors, String sort, String direction, int limit, int offset) {
        Map<String, Object> parameters = fixture.noteParameters();
        parameters.put("query", query);
        parameters.put("authorIds", authors);
        parameters.put("sort", sort);
        parameters.put("dir", direction);
        parameters.put("limit", limit);
        parameters.put("offset", offset);
        assertEquals(fixture.legacy.selectList("NoteMapper.getVisibleNotesPage", parameters),
            visiblePage(query, authors, sort, direction, limit, offset));
        assertEquals(fixture.legacy.count("NoteMapper.countVisibleNotes", parameters), visibleCount(query, authors));
        assertEquals(fixture.legacy.selectList("NoteMapper.getWorkspaceNotesPage", parameters),
            workspacePage(query, authors, sort, direction, limit, offset));
        assertEquals(fixture.legacy.count("NoteMapper.countWorkspaceNotes", parameters), workspaceCount(query, authors));
    }

    private void assertSearchParity(String query, int limit, int offset) {
        Map<String, Object> parameters = fixture.noteParameters();
        parameters.put("query", query);
        parameters.put("limit", limit);
        parameters.put("offset", offset);
        assertEquals(fixture.legacy.selectList("NoteMapper.searchVisible", parameters), search(query, limit, offset));
    }

    private void assertPersonCountParity(int person, LocalDateTime reference, int limit) {
        Map<String, Object> parameters = fixture.noteParameters();
        parameters.put("personId", person);
        parameters.put("reference", reference);
        parameters.put("sourceLimit", limit);
        assertEquals(fixture.legacy.count("NoteMapper.countOwnPrivateNotesForPersonEvidence", parameters),
            personCount(person, reference, limit));
    }

    private void assertCompanyCountParity(int company, LocalDateTime reference, int limit) {
        Map<String, Object> parameters = fixture.noteParameters();
        parameters.put("companyId", company);
        parameters.put("reference", reference);
        parameters.put("sourceLimit", limit);
        assertEquals(fixture.legacy.count("NoteMapper.countOwnPrivateNotesForCompanyEvidence", parameters),
            companyCount(company, reference, limit));
    }

    private List<Note> visiblePage(String query, List<Integer> authors, String sort, String direction, int limit, int offset) {
        return fixture.noteMapper.getVisibleNotesPage(fixture.workspaceId, scope(), fixture.userId,
            query, authors, sort, direction, limit, offset);
    }

    private List<Note> workspacePage(String query, List<Integer> authors, String sort, String direction, int limit, int offset) {
        return fixture.noteMapper.getWorkspaceNotesPage(fixture.workspaceId, scope(), query, authors, sort, direction, limit, offset);
    }

    private long visibleCount(String query, List<Integer> authors) {
        return fixture.noteMapper.countVisibleNotes(fixture.workspaceId, scope(), fixture.userId, query, authors);
    }

    private long workspaceCount(String query, List<Integer> authors) {
        return fixture.noteMapper.countWorkspaceNotes(fixture.workspaceId, scope(), query, authors);
    }

    private List<Note> search(String query, int limit, int offset) {
        return fixture.noteMapper.searchVisible(fixture.workspaceId, scope(), query, fixture.userId, limit, offset);
    }

    private int personCount(int person, LocalDateTime reference, int limit) {
        return fixture.noteMapper.countOwnPrivateNotesForPersonEvidence(
            fixture.workspaceId, scope(), person, fixture.userId, reference, limit);
    }

    private int companyCount(int company, LocalDateTime reference, int limit) {
        return fixture.noteMapper.countOwnPrivateNotesForCompanyEvidence(
            fixture.workspaceId, scope(), company, fixture.userId, reference, limit);
    }

    private String scope() {
        return fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
    }

    private static LocalDateTime reference() {
        return LocalDateTime.of(2026, 10, 1, 0, 0);
    }

    private static List<Integer> ids(List<Note> notes) {
        return notes.stream().map(Note::getId).toList();
    }
}

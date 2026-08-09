package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.HistoryImportColumnMapping;
import ooo.klae.connex.backend.dto.HistoryImportPreviewResult;
import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.dto.HistoryImportResult;

@Transactional(isolation = Isolation.READ_COMMITTED)
class InteractionHistoryImportPersistenceTest extends AbstractServiceTest {

    @Autowired private InteractionHistoryImportService importService;
    @Autowired private PersonService personService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void historyCsvThreeIdenticalCyclesKeepEveryArtifactStable() {
        Company company = newCompany();
        Person draft = new Person();
        draft.setName("History replay");
        draft.setEmail("history-replay-" + unique() + "@example.test");
        draft.setCompany(company);
        Person person = personService.create(draft);
        Tag tag = newTag();
        personService.addTag(person.getId(), tag.getId());
        newNotification(workspace.getId(), currentUser.getId());
        HistoryImportRequest activity = activityRequest(person.getEmail());
        HistoryImportRequest note = noteRequest(person.getEmail());
        HistoryImportRequest task = taskRequest(person.getEmail());
        User secondActor = newUser();
        workspaceMapper.updateMemberRole(
            workspace.getId(), secondActor.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
        User thirdActor = newUser();
        workspaceMapper.updateMemberRole(
            workspace.getId(), thirdActor.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());

        HistoryImportResult firstActivity = previewAndCommitActivity(activity);
        HistoryImportResult firstNote = previewAndCommitNote(note);
        HistoryImportResult firstTask = previewAndCommitTask(task);
        ReplayArtifacts afterFirst = replayArtifacts(person.getId());

        authenticateAs(secondActor, workspace.getId());
        HistoryImportResult secondActivity = previewAndCommitActivity(activity);
        HistoryImportResult secondNote = previewAndCommitNote(note);
        HistoryImportResult secondTask = previewAndCommitTask(task);
        ReplayArtifacts afterSecond = replayArtifacts(person.getId());

        authenticateAs(thirdActor, workspace.getId());
        HistoryImportResult thirdActivity = previewAndCommitActivity(activity);
        HistoryImportResult thirdNote = previewAndCommitNote(note);
        HistoryImportResult thirdTask = previewAndCommitTask(task);

        assertEquals(1, firstActivity.created());
        assertEquals(1, firstNote.created());
        assertEquals(1, firstTask.created());
        assertEquals(1, secondActivity.skipped());
        assertEquals(1, secondNote.skipped());
        assertEquals(1, secondTask.skipped());
        assertEquals(1, thirdActivity.skipped());
        assertEquals(1, thirdNote.skipped());
        assertEquals(1, thirdTask.skipped());
        assertEquals(1, afterFirst.activities());
        assertEquals(1, afterFirst.notes());
        assertEquals(1, afterFirst.tasks());
        assertTrue(afterFirst.tags() >= 2);
        assertTrue(afterFirst.relationships() >= 1);
        assertTrue(afterFirst.notifications() >= 1);
        assertEquals(3, afterFirst.relationshipEvidenceEvents());
        assertEquals(afterFirst, afterSecond);
        assertEquals(afterFirst, replayArtifacts(person.getId()));
    }

    private HistoryImportResult previewAndCommitActivity(HistoryImportRequest request) {
        HistoryImportPreviewResult preview = importService.previewActivities(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        return importService.commitActivities(request);
    }

    private HistoryImportResult previewAndCommitNote(HistoryImportRequest request) {
        HistoryImportPreviewResult preview = importService.previewNotes(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        return importService.commitNotes(request);
    }

    private HistoryImportResult previewAndCommitTask(HistoryImportRequest request) {
        HistoryImportPreviewResult preview = importService.previewTasks(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        return importService.commitTasks(request);
    }

    private static HistoryImportRequest activityRequest(String email) {
        return new HistoryImportRequest(
            List.of(row(
                "when", "2026-01-02T03:04:05Z",
                "email", email,
                "subject", "Replay activity",
                "source", "release-replay-activity")),
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("subject", "subject"),
                mapping("source", "sourceId")),
            null,
            null);
    }

    private static HistoryImportRequest noteRequest(String email) {
        return new HistoryImportRequest(
            List.of(row(
                "when", "2026-01-03T04:05:06Z",
                "email", email,
                "content", "Replay note",
                "source", "release-replay-note")),
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("content", "content"),
                mapping("source", "sourceId")),
            null,
            null);
    }

    private static HistoryImportRequest taskRequest(String email) {
        return new HistoryImportRequest(
            List.of(row(
                "when", "2026-01-04T05:06:07Z",
                "email", email,
                "description", "Replay task",
                "source", "release-replay-task",
                "due", "2026-02-01",
                "completed", "false")),
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("description", "description"),
                mapping("source", "sourceId"),
                mapping("due", "dueDate"),
                mapping("completed", "completed")),
            null,
            null);
    }

    private static Map<String, String> row(String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(values[index], values[index + 1]);
        }
        return row;
    }

    private static HistoryImportColumnMapping mapping(String column, String field) {
        return new HistoryImportColumnMapping(column, field);
    }

    private ReplayArtifacts replayArtifacts(int personId) {
        int records = rowCount(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM company WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM deal WHERE workspace_id = ?", workspace.getId());
        int tags = rowCount(
                "SELECT COUNT(*) FROM tag WHERE workspace_id = ?", workspace.getId())
            + rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", personId);
        int relationships = rowCount(
                "SELECT COUNT(*) FROM person_employment WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId)
            + rowCount(
                "SELECT COUNT(*) FROM deal_person dp JOIN deal d ON d.id = dp.deal_id "
                    + "WHERE d.workspace_id = ? AND dp.person_id = ?",
                workspace.getId(),
                personId);
        return new ReplayArtifacts(
            records,
            rowCount(
                "SELECT COUNT(*) FROM activity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            rowCount(
                "SELECT COUNT(*) FROM note WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            rowCount(
                "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            tags,
            relationships,
            rowCount(
                "SELECT COUNT(*) FROM notification WHERE workspace_id = ?",
                workspace.getId()),
            scoringService.contactEvidence(
                workspace.getId(), personId, currentUser.getId())
                .totals()
                .contributorCount());
    }

    private int rowCount(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private record ReplayArtifacts(
        int records,
        int activities,
        int notes,
        int tasks,
        int tags,
        int relationships,
        int notifications,
        int relationshipEvidenceEvents
    ) {}
}

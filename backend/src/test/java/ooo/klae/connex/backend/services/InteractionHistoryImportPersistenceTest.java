package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.HistoryImportColumnMapping;
import ooo.klae.connex.backend.dto.HistoryImportPreviewResult;
import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.dto.HistoryImportResult;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InteractionHistoryImportPersistenceTest extends AbstractServiceTest {

    @Autowired private InteractionHistoryImportService importService;
    @Autowired private PersonService personService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private final List<Integer> createdUserIds = new ArrayList<>();
    private Integer persistedCompanyId;
    private Integer persistedPersonId;
    private Integer persistedTagId;
    private Integer persistedNotificationId;

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (workspace != null) {
            for (Integer userId : createdUserIds) {
                jdbcTemplate.update(
                    "DELETE FROM historical_notification_baseline "
                        + "WHERE workspace_id = ? AND recipient_id = ?",
                    workspace.getId(),
                    userId);
            }
        }
        if (workspace != null && persistedPersonId != null) {
            jdbcTemplate.update(
                "DELETE FROM activity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                persistedPersonId);
            jdbcTemplate.update(
                "DELETE FROM note WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                persistedPersonId);
            jdbcTemplate.update(
                "DELETE FROM task WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                persistedPersonId);
        }
        if (workspace != null && persistedNotificationId != null) {
            jdbcTemplate.update(
                "DELETE FROM notification WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedNotificationId);
        }
        if (workspace != null && persistedPersonId != null) {
            jdbcTemplate.update(
                "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedPersonId);
        }
        if (workspace != null && persistedTagId != null) {
            jdbcTemplate.update(
                "DELETE FROM tag WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedTagId);
        }
        if (workspace != null && persistedCompanyId != null) {
            jdbcTemplate.update(
                "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedCompanyId);
        }
        if (workspace != null) {
            for (Integer userId : createdUserIds.reversed()) {
                jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(),
                    userId);
            }
        }
        for (Integer userId : createdUserIds.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    @Test
    void historyCsvThreeIdenticalCyclesKeepEveryArtifactStable() {
        Company company = newCompany();
        persistedCompanyId = company.getId();
        Person draft = new Person();
        draft.setName("History replay");
        draft.setEmail("history-replay-" + unique() + "@example.test");
        draft.setCompany(company);
        Person person = personService.create(draft);
        persistedPersonId = person.getId();
        Tag tag = newTag();
        persistedTagId = tag.getId();
        personService.addTag(person.getId(), tag.getId());
        Notification notification = newNotification(
            workspace.getId(), currentUser.getId());
        persistedNotificationId = notification.getId();
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
        HistoryReplayState afterFirst = replayState(person.getId());

        authenticateAs(secondActor, workspace.getId());
        HistoryImportResult secondActivity = previewAndCommitActivity(activity);
        HistoryImportResult secondNote = previewAndCommitNote(note);
        HistoryImportResult secondTask = previewAndCommitTask(task);
        HistoryReplayState afterSecond = replayState(person.getId());

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
        assertEquals(1, afterFirst.artifacts().activities());
        assertEquals(1, afterFirst.artifacts().notes());
        assertEquals(1, afterFirst.artifacts().tasks());
        assertTrue(afterFirst.artifacts().tags() >= 2);
        assertTrue(afterFirst.artifacts().relationships() >= 1);
        assertTrue(afterFirst.artifacts().notifications() >= 1);
        assertEquals(3, afterFirst.artifacts().relationshipEvidenceEvents());
        assertEquals(afterFirst, afterSecond);
        assertEquals(afterFirst, replayState(person.getId()));
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

    private HistoryReplayState replayState(int personId) {
        return new HistoryReplayState(
            replayArtifacts(personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, owner_id, name, email, phone, company_id, title, "
                    + "image_url, created_at, updated_at FROM person "
                    + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, type, subject, notes, person_id, deal_id, "
                    + "created_by_id, timestamp, history_import_key, history_payload_hash, "
                    + "history_source_system, history_source_id, history_source_row_ref, "
                    + "history_imported_at FROM activity WHERE workspace_id = ? "
                    + "AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, content, title, visibility, author_id, person_id, "
                    + "deal_id, created_at, updated_at, history_import_key, history_payload_hash, "
                    + "history_source_system, history_source_id, history_source_row_ref, "
                    + "history_imported_at FROM note WHERE workspace_id = ? "
                    + "AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, description, completed, status, position, due_date, "
                    + "assigned_to_id, person_id, deal_id, created_at, updated_at, "
                    + "history_import_key, history_payload_hash, history_source_system, "
                    + "history_source_id, history_source_row_ref, history_imported_at FROM task "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT t.id, t.workspace_id, t.name, t.color, pt.person_id "
                    + "FROM tag t JOIN person_tag pt ON pt.tag_id = t.id "
                    + "WHERE t.workspace_id = ? AND pt.person_id = ? ORDER BY t.id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, person_id, company_id, company_name, title, "
                    + "started_at, ended_at, created_at FROM person_employment "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, recipient_id, type, category, severity, "
                    + "template_version, title, body, actor_id, actor_label, source_type, "
                    + "source_id, source_label, context_type, context_id, context_label, "
                    + "action_url, data, dedupe_key, triggered_at, read_at, dismissed_at, "
                    + "resolved_at, created_at, updated_at FROM notification "
                    + "WHERE workspace_id = ? AND recipient_id = ? ORDER BY id",
                workspace.getId(),
                currentUser.getId()));
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

    private record HistoryReplayState(
        ReplayArtifacts artifacts,
        List<Map<String, Object>> people,
        List<Map<String, Object>> activities,
        List<Map<String, Object>> notes,
        List<Map<String, Object>> tasks,
        List<Map<String, Object>> tags,
        List<Map<String, Object>> employmentRelationships,
        List<Map<String, Object>> notifications
    ) {}
}

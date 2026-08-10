package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.HistoryImportProvenance;
import ooo.klae.connex.backend.beans.HistoryImportWrite;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.HistoryImportColumnMapping;
import ooo.klae.connex.backend.dto.HistoryImportPreviewResult;
import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.dto.HistoryImportResult;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

@ExtendWith(MockitoExtension.class)
class InteractionHistoryImportServiceTest {

    private static final int WORKSPACE_ID = 7;
    private static final int PERSON_ID = 101;
    private static final String PROOF = "a".repeat(64);
    private static final Instant EVALUATION_INSTANT =
        Instant.parse("2026-07-30T12:00:00Z");

    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private DuplicatePreflightService duplicatePreflightService;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private MatchingService matchingService;
    @Mock private PersonMapper personMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private NoteMapper noteMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private NotificationReconciliationService notificationReconciliationService;
    @Mock private AuditService auditService;

    private final Deque<DuplicatePreflightService.ImportPreviewSession> previews =
        new ArrayDeque<>();
    private final Deque<DuplicatePreflightService.ImportCommitSession> commits =
        new ArrayDeque<>();
    private final List<HistoryImportWrite> activityWrites = new ArrayList<>();
    private final List<HistoryImportWrite> noteWrites = new ArrayList<>();
    private final List<HistoryImportWrite> taskWrites = new ArrayList<>();

    private DuplicatePreflightService.ImportCommitAdmission admission;
    private InteractionHistoryImportService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(EVALUATION_INSTANT, ZoneOffset.UTC);
        admission = mock(DuplicatePreflightService.ImportCommitAdmission.class);
        service = new InteractionHistoryImportService(
            workspaceService,
            authService,
            duplicatePreflightService,
            duplicateDecisionLockService,
            matchingService,
            personMapper,
            activityMapper,
            noteMapper,
            taskMapper,
            notificationReconciliationService,
            auditService,
            clock);

        User actor = new User();
        actor.setId(42);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(authService.getCurrentUser()).thenReturn(actor);
        lenient().when(matchingService.normalizeIdentifier(any(IdentityKind.class), anyString()))
            .thenAnswer(invocation ->
                Optional.of(invocation.getArgument(1, String.class).trim().toLowerCase()));
        lenient().when(duplicatePreflightService.beginImportPreview(
                anyList(), anyList(), anyString()))
            .thenAnswer(invocation -> previews.removeFirst());
        lenient().when(duplicatePreflightService.claimImportCommit(any(), anyString()))
            .thenReturn(admission);
        lenient().when(duplicatePreflightService.beginImportCommit(
                anyList(), anyList(), anyString(), eq(admission)))
            .thenAnswer(invocation -> commits.removeFirst());
        lenient().when(personMapper.getOwnedPersonByIdForUpdate(eq(WORKSPACE_ID), anyInt()))
            .thenAnswer(invocation ->
                person(invocation.getArgument(1, Integer.class), WORKSPACE_ID));
        NotificationReconciliationService.HistoricalExpectationSnapshot emptySnapshot =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(Map.of());
        lenient().when(notificationReconciliationService.historicalExpectationSnapshot(
                WORKSPACE_ID, EVALUATION_INSTANT))
            .thenReturn(emptySnapshot);
        lenient().when(notificationReconciliationService.historicalExpectationSnapshot(
                eq(WORKSPACE_ID), eq(EVALUATION_INSTANT), any()))
            .thenReturn(emptySnapshot);
        lenient().when(activityMapper.insertHistoryBatch(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                activityWrites.addAll(invocation.getArgument(1));
                return 1;
            });
        lenient().when(noteMapper.insertHistoryBatch(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                noteWrites.addAll(invocation.getArgument(1));
                return 1;
            });
        lenient().when(taskMapper.insertHistoryBatch(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                taskWrites.addAll(invocation.getArgument(1));
                return 1;
            });
        lenient().when(activityMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<String> keys = invocation.getArgument(1);
                return activityWrites.stream()
                    .filter(write -> keys.contains(write.getHistoryImportKey()))
                    .map(write -> provenance(81 + activityWrites.indexOf(write), write))
                    .toList();
            });
        lenient().when(noteMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<String> keys = invocation.getArgument(1);
                return noteWrites.stream()
                    .filter(write -> keys.contains(write.getHistoryImportKey()))
                    .map(write -> provenance(86 + noteWrites.indexOf(write), write))
                    .toList();
            });
        lenient().when(taskMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<String> keys = invocation.getArgument(1);
                return taskWrites.stream()
                    .filter(write -> keys.contains(write.getHistoryImportKey()))
                    .map(write -> provenance(91 + taskWrites.indexOf(write), write))
                    .toList();
            });
        lenient().when(taskMapper.nextTaskPosition(WORKSPACE_ID, "todo")).thenReturn(4);
        lenient().when(taskMapper.nextTaskPosition(WORKSPACE_ID, "done")).thenReturn(9);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void importsAllThreeKindsWithCanonicalHistoricalWritesAndTaskPositions() {
        HistoryImportRequest activity = activityRequest(
            "activity-source", "Followed up", "2026-01-02 03:04:05.123456");
        HistoryImportRequest note = noteRequest(
            "note-source", "Imported context", "2026-02-03T04:05:06Z");
        HistoryImportRequest task = taskRequest(
            "task-source", "Send the archive", "2026-03-04T05:06:07.654321Z", "true");

        HistoryImportResult activityResult =
            previewAndCommitActivity(activity, strongResponse(PERSON_ID));
        HistoryImportResult noteResult =
            previewAndCommitNote(note, strongResponse(PERSON_ID));
        HistoryImportResult taskResult =
            inReadCommitted(() -> previewAndCommitTask(task, strongResponse(PERSON_ID)));

        assertEquals(1, activityResult.created());
        assertEquals(1, noteResult.created());
        assertEquals(1, taskResult.created());

        HistoryImportWrite activityWrite = activityWrites.getFirst();
        assertEquals("2026-01-02 03:04:05", activityWrite.getOccurredAt());
        assertEquals("other", activityWrite.getType());
        assertEquals("Followed up", activityWrite.getSubject());
        assertEquals(PERSON_ID, activityWrite.getPersonId());
        assertEquals(42, activityWrite.getActorId());
        assertEquals("activity-source", activityWrite.getHistorySourceId());

        HistoryImportWrite noteWrite = noteWrites.getFirst();
        assertEquals("2026-02-03 04:05:06", noteWrite.getOccurredAt());
        assertEquals("Imported context", noteWrite.getContent());
        assertEquals(PERSON_ID, noteWrite.getPersonId());

        HistoryImportWrite taskWrite = taskWrites.getFirst();
        assertEquals("2026-03-04 05:06:07", taskWrite.getOccurredAt());
        assertTrue(taskWrite.isCompleted());
        assertEquals("done", taskWrite.getStatus());
        assertEquals(9, taskWrite.getPosition());
        assertEquals(42, taskWrite.getActorId());

        verify(taskMapper).lockTaskBoard(WORKSPACE_ID);
        verify(notificationReconciliationService,
            org.mockito.Mockito.times(6)).historicalExpectationSnapshot(
                WORKSPACE_ID, EVALUATION_INSTANT);
        verify(notificationReconciliationService,
            org.mockito.Mockito.times(3)).historicalExpectationSnapshot(
                eq(WORKSPACE_ID), eq(EVALUATION_INSTANT), any());
        verify(notificationReconciliationService,
            org.mockito.Mockito.times(3)).persistHistoricalBaselines(
                eq(WORKSPACE_ID), any(), any(), any(), anyString());
        verify(auditService, org.mockito.Mockito.times(3)).record(
            anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void matchesOnlySafeOwnedPeopleAndRequiresReviewForUnsafeDecisions() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            rows.add(Map.of(
                "when", "2026-01-01T00:00:00Z",
                "email", "person" + index + "@example.com",
                "subject", "Row " + index,
                "source", "source-" + index));
        }
        HistoryImportRequest request = new HistoryImportRequest(
            rows,
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("subject", "subject"),
                mapping("source", "sourceId")),
            Map.of(5, PERSON_ID, 6, PERSON_ID, 7, 999),
            null);
        when(personMapper.getByIds(WORKSPACE_ID, List.of(PERSON_ID, 999)))
            .thenReturn(List.of(person(PERSON_ID, WORKSPACE_ID)));
        queuePreview(List.of(
            strongResponse(PERSON_ID),
            response(List.of(
                candidate(PERSON_ID, true, DuplicateMatchStrength.STRONG),
                candidate(201, true, DuplicateMatchStrength.WEAK)), false),
            response(List.of(
                candidate(PERSON_ID, true, DuplicateMatchStrength.STRONG),
                candidate(102, true, DuplicateMatchStrength.STRONG)), false),
            response(List.of(
                candidate(103, false, DuplicateMatchStrength.STRONG)), false),
            response(List.of(), false),
            response(List.of(
                candidate(102, true, DuplicateMatchStrength.STRONG)), false),
            response(List.of(), false),
            response(List.of(), false)),
            PROOF);

        HistoryImportPreviewResult preview = service.previewActivities(request);

        assertEquals(3, preview.toCreate());
        assertEquals(5, preview.needsReview());
        assertEquals("ready", preview.rows().get(0).status());
        assertEquals("ready", preview.rows().get(1).status());
        assertEquals("needs_review", preview.rows().get(2).status());
        assertEquals("needs_review", preview.rows().get(3).status());
        assertEquals("needs_review", preview.rows().get(4).status());
        assertEquals("needs_review", preview.rows().get(5).status());
        assertEquals("ready", preview.rows().get(6).status());
        assertEquals("needs_review", preview.rows().get(7).status());
    }

    @Test
    void commitAuditsInvalidAndReviewRowsAsDistinctExactOutcomes() {
        HistoryImportRequest valid = activityRequest(
            "audit-ready", "Ready row", "2026-01-01T00:00:00Z");
        Map<String, String> review = new LinkedHashMap<>(valid.getRows().getFirst());
        review.put("source", "audit-review");
        review.put("subject", "Review row");
        Map<String, String> invalid = new LinkedHashMap<>(valid.getRows().getFirst());
        invalid.put("source", "audit-invalid");
        invalid.put("when", "not-a-timestamp");
        HistoryImportRequest request = new HistoryImportRequest(
            List.of(valid.getRows().getFirst(), review, invalid),
            valid.getMapping(),
            null,
            PROOF);
        queueCommit(List.of(
            strongResponse(PERSON_ID),
            response(List.of(), false)));

        HistoryImportResult result = service.commitActivities(request);

        assertEquals(1, result.created());
        assertEquals(0, result.skipped());
        assertEquals(2, result.failed().size());
        verify(auditService).record(
            eq("import.history.activity"),
            eq("activity"),
            isNull(),
            eq("CSV history import"),
            eq("Imported historical activity rows: 1 created, 0 skipped, 1 failed, 1 remained for review"),
            org.mockito.ArgumentMatchers.argThat(changes ->
                changes instanceof Map<?, ?> outcomes
                    && outcomes.keySet().equals(Set.of(
                        "created",
                        "skipped",
                        "failed",
                        "remainedForReview",
                        "sourceSystem"))
                    && Integer.valueOf(1).equals(outcomes.get("created"))
                    && Integer.valueOf(0).equals(outcomes.get("skipped"))
                    && Integer.valueOf(1).equals(outcomes.get("failed"))
                    && Integer.valueOf(1).equals(outcomes.get("remainedForReview"))
                    && "csv".equals(outcomes.get("sourceSystem"))));
    }

    @Test
    void manualReviewResolvesStrongAmbiguityWithoutOverridingUnrelatedEvidence() {
        HistoryImportRequest first = activityRequest(
            "manual-ambiguous", "Reviewed ambiguity", "2026-01-01T00:00:00Z");
        HistoryImportRequest second = activityRequest(
            "manual-conflict", "Conflicting selection", "2026-01-02T00:00:00Z");
        HistoryImportRequest request = new HistoryImportRequest(
            List.of(first.getRows().getFirst(), second.getRows().getFirst()),
            first.getMapping(),
            Map.of(0, PERSON_ID, 1, PERSON_ID),
            null);
        when(personMapper.getByIds(WORKSPACE_ID, List.of(PERSON_ID)))
            .thenReturn(List.of(person(PERSON_ID, WORKSPACE_ID)));
        queuePreview(List.of(
            response(List.of(
                candidate(PERSON_ID, true, DuplicateMatchStrength.STRONG),
                candidate(102, true, DuplicateMatchStrength.STRONG)), false),
            strongResponse(102)),
            PROOF);

        HistoryImportPreviewResult preview = service.previewActivities(request);

        assertEquals(1, preview.toCreate());
        assertEquals(1, preview.needsReview());
        assertEquals("ready", preview.rows().getFirst().status());
        assertEquals(PERSON_ID, preview.rows().getFirst().participantId());
        assertEquals("needs_review", preview.rows().get(1).status());
    }

    @Test
    void validatesBoundsFutureDatesAndStrictTaskValuesBeforeWriting() {
        List<Map<String, String>> tooManyRows = new ArrayList<>();
        Map<String, String> row = Map.of(
            "when", "2026-01-01T00:00:00Z",
            "email", "person@example.com",
            "subject", "Imported");
        for (int index = 0; index < 5001; index++) {
            tooManyRows.add(row);
        }
        HistoryImportRequest oversized = new HistoryImportRequest(
            tooManyRows,
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("subject", "subject")),
            null,
            null);

        assertThrows(
            BadRequestException.class,
            () -> service.previewActivities(oversized));

        HistoryImportRequest invalidTask = taskRequest(
            "future-task", "Impossible task", "2027-01-01T00:00:00+09:00", "yes");
        invalidTask.getRows().getFirst().put("due", "2026/01/02");
        queuePreview(List.of(), PROOF);

        HistoryImportPreviewResult preview = service.previewTasks(invalidTask);

        assertEquals(1, preview.invalid());
        assertTrue(preview.rows().getFirst().errors().stream()
            .anyMatch(error -> error.contains("offset must be UTC")));
        assertTrue(preview.rows().getFirst().errors().stream()
            .anyMatch(error -> error.contains("dueDate")));
        assertTrue(preview.rows().getFirst().errors().stream()
            .anyMatch(error -> error.contains("completed")));
        verify(activityMapper, never()).insertHistoryBatch(anyInt(), anyList());
        verify(taskMapper, never()).insertHistoryBatch(anyInt(), anyList());
    }

    @Test
    void replaysAllThreeKindsAndRejectsASourceIdPayloadCollision() {
        HistoryImportRequest activity = activityRequest(
            "replay-activity", "Original", "2026-01-01T00:00:00Z");
        HistoryImportRequest note = noteRequest(
            "replay-note", "Original note", "2026-01-01T00:00:00Z");
        HistoryImportRequest task = taskRequest(
            "replay-task", "Original task", "2026-01-01T00:00:00Z", "false");

        previewAndCommitActivity(activity, strongResponse(PERSON_ID));
        previewAndCommitNote(note, strongResponse(PERSON_ID));
        inReadCommitted(() -> previewAndCommitTask(task, strongResponse(PERSON_ID)));

        when(activityMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenReturn(List.of(provenance(activityWrites.getFirst())));
        when(noteMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenReturn(List.of(provenance(noteWrites.getFirst())));
        when(taskMapper.findHistoryImports(eq(WORKSPACE_ID), anyList()))
            .thenReturn(List.of(provenance(taskWrites.getFirst())));

        HistoryImportResult activityReplay =
            previewAndCommitActivity(activity, strongResponse(PERSON_ID));
        HistoryImportResult noteReplay =
            previewAndCommitNote(note, strongResponse(PERSON_ID));
        HistoryImportResult taskReplay =
            inReadCommitted(() -> previewAndCommitTask(task, strongResponse(PERSON_ID)));

        assertEquals(1, activityReplay.skipped());
        assertEquals(1, noteReplay.skipped());
        assertEquals(1, taskReplay.skipped());
        assertEquals(1, activityWrites.size());
        assertEquals(1, noteWrites.size());
        assertEquals(1, taskWrites.size());

        HistoryImportRequest collision = activityRequest(
            "replay-activity", "Changed payload", "2026-01-01T00:00:00Z");
        queuePreview(List.of(strongResponse(PERSON_ID)), PROOF);

        HistoryImportPreviewResult collisionPreview =
            service.previewActivities(collision);

        assertEquals(1, collisionPreview.invalid());
        assertTrue(collisionPreview.rows().getFirst().errors().getFirst()
            .contains("different data"));
    }

    @Test
    void sourceLessIdenticalRowsUseStableOccurrenceKeysInsteadOfCollapsing() {
        HistoryImportRequest first = activityRequest(
            null, "Repeated event", "2026-01-01T00:00:00Z");
        Map<String, String> duplicate = new LinkedHashMap<>(first.getRows().getFirst());
        first.setRows(List.of(first.getRows().getFirst(), duplicate));

        queuePreview(
            List.of(strongResponse(PERSON_ID), strongResponse(PERSON_ID)),
            PROOF);
        HistoryImportPreviewResult preview = service.previewActivities(first);

        assertEquals(2, preview.toCreate());
        first.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(
            strongResponse(PERSON_ID), strongResponse(PERSON_ID)));
        HistoryImportResult result = service.commitActivities(first);

        assertEquals(2, result.created());
        assertNotEquals(
            activityWrites.get(0).getHistoryImportKey(),
            activityWrites.get(1).getHistoryImportKey());
    }

    @Test
    void sourceLessOccurrenceKeysStayStableAcrossProgressiveManualReview() {
        HistoryImportRequest first = sourceLessDuplicateRequest();
        DuplicatePreflightResponse unresolved = response(List.of(), false);
        DuplicatePreflightResponse matched = strongResponse(PERSON_ID);

        queuePreview(List.of(unresolved, matched), PROOF);
        HistoryImportPreviewResult firstPreview = service.previewActivities(first);
        assertEquals(1, firstPreview.toCreate());
        assertEquals(1, firstPreview.needsReview());

        first.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(unresolved, matched));
        HistoryImportResult firstResult = service.commitActivities(first);
        assertEquals(1, firstResult.created());

        HistoryImportRequest second = sourceLessDuplicateRequest();
        second.setLinks(Map.of(0, PERSON_ID));
        when(personMapper.getByIds(WORKSPACE_ID, List.of(PERSON_ID)))
            .thenReturn(List.of(person(PERSON_ID, WORKSPACE_ID)));
        queuePreview(List.of(unresolved, matched), PROOF);

        HistoryImportPreviewResult secondPreview = service.previewActivities(second);

        assertEquals(1, secondPreview.toCreate());
        assertEquals(1, secondPreview.alreadyImported());
        second.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(unresolved, matched));
        HistoryImportResult secondResult = service.commitActivities(second);
        assertEquals(1, secondResult.created());
        assertEquals(1, secondResult.skipped());
        assertEquals(2, activityWrites.size());
    }

    @Test
    void proofContextChangesWithRawRowsAndAClaimCannotBeReused() {
        HistoryImportRequest request = activityRequest(
            "proof-source", "Original", "2026-01-01T00:00:00Z");
        queuePreview(List.of(strongResponse(PERSON_ID)), PROOF);
        service.previewActivities(request);

        ArgumentCaptor<String> previewContext = ArgumentCaptor.forClass(String.class);
        verify(duplicatePreflightService).beginImportPreview(
            anyList(), anyList(), previewContext.capture());

        request.getRows().getFirst().put("subject", "Changed");
        request.setDuplicateReviewProof(PROOF);
        when(duplicatePreflightService.claimImportCommit(eq(PROOF), anyString()))
            .thenAnswer(invocation -> {
                assertNotEquals(
                    previewContext.getValue(),
                    invocation.getArgument(1, String.class));
                throw new ConflictException("Preview changed");
            });

        assertThrows(
            ConflictException.class,
            () -> service.commitActivities(request));

        HistoryImportRequest oneUse = activityRequest(
            "one-use", "One use", "2026-01-01T00:00:00Z");
        oneUse.setDuplicateReviewProof("b".repeat(64));
        queueCommit(List.of(strongResponse(PERSON_ID)));
        when(duplicatePreflightService.claimImportCommit(
                eq("b".repeat(64)), anyString()))
            .thenReturn(admission)
            .thenThrow(new ConflictException("Proof already used"));

        assertEquals(1, service.commitActivities(oneUse).created());
        assertThrows(
            ConflictException.class,
            () -> service.commitActivities(oneUse));
    }

    @Test
    void baselineWriteFailureAbortsBeforeTheSummaryAudit() {
        HistoryImportRequest request = activityRequest(
            "baseline-failure", "Rollback", "2026-01-01T00:00:00Z");
        request.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(strongResponse(PERSON_ID)));
        doThrow(new IllegalStateException("baseline write failed"))
            .when(notificationReconciliationService)
            .persistHistoricalBaselines(
                eq(WORKSPACE_ID), any(), any(), any(), anyString());

        assertThrows(
            IllegalStateException.class,
            () -> service.commitActivities(request));

        verify(activityMapper).insertHistoryBatch(eq(WORKSPACE_ID), anyList());
        verify(auditService, never()).record(
            anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void concurrentRelevantNotificationChangeRollsBackBeforeBaselineAndAudit() {
        HistoryImportRequest request = activityRequest(
            "counterfactual-conflict", "Rollback", "2026-01-01T00:00:00Z");
        request.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(strongResponse(PERSON_ID)));
        NotificationReconciliationService.HistoricalExpectationKey changed =
            new NotificationReconciliationService.HistoricalExpectationKey(
                WORKSPACE_ID,
                42,
                "relationship.cooling:5:" + PERSON_ID);
        NotificationReconciliationService.HistoricalExpectationSnapshot counterfactual =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(
                    changed,
                    new NotificationReconciliationService.HistoricalExpectation(
                        NotificationReconciliationService.RELATIONSHIP_TYPE,
                        "warning")));
        when(notificationReconciliationService.historicalExpectationSnapshot(
                eq(WORKSPACE_ID), eq(EVALUATION_INSTANT), any()))
            .thenReturn(counterfactual);

        assertThrows(
            ConflictException.class,
            () -> service.commitActivities(request));

        verify(activityMapper).insertHistoryBatch(eq(WORKSPACE_ID), anyList());
        verify(notificationReconciliationService, never())
            .persistHistoricalBaselines(
                eq(WORKSPACE_ID), any(), any(), any(), anyString());
        verify(auditService, never()).record(
            anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    private HistoryImportResult previewAndCommitActivity(
            HistoryImportRequest request,
            DuplicatePreflightResponse response) {
        queuePreview(List.of(response), PROOF);
        HistoryImportPreviewResult preview = service.previewActivities(request);
        assertEquals(PROOF, preview.duplicateReviewProof());
        request.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(response));
        return service.commitActivities(request);
    }

    private static HistoryImportRequest sourceLessDuplicateRequest() {
        HistoryImportRequest request = activityRequest(
            null, "Repeated event", "2026-01-01T00:00:00Z");
        request.setRows(List.of(
            request.getRows().getFirst(),
            new LinkedHashMap<>(request.getRows().getFirst())));
        return request;
    }

    private HistoryImportResult previewAndCommitNote(
            HistoryImportRequest request,
            DuplicatePreflightResponse response) {
        queuePreview(List.of(response), PROOF);
        HistoryImportPreviewResult preview = service.previewNotes(request);
        assertEquals(PROOF, preview.duplicateReviewProof());
        request.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(response));
        return service.commitNotes(request);
    }

    private HistoryImportResult previewAndCommitTask(
            HistoryImportRequest request,
            DuplicatePreflightResponse response) {
        queuePreview(List.of(response), PROOF);
        HistoryImportPreviewResult preview = service.previewTasks(request);
        assertEquals(PROOF, preview.duplicateReviewProof());
        request.setDuplicateReviewProof(PROOF);
        queueCommit(List.of(response));
        return service.commitTasks(request);
    }

    private void queuePreview(
            List<DuplicatePreflightResponse> responses,
            String proof) {
        DuplicatePreflightService.ImportPreviewSession session =
            mock(DuplicatePreflightService.ImportPreviewSession.class);
        when(session.responses()).thenReturn(responses);
        when(session.reviewProof()).thenReturn(proof);
        previews.addLast(session);
    }

    private void queueCommit(List<DuplicatePreflightResponse> responses) {
        DuplicatePreflightService.ImportCommitSession session =
            mock(DuplicatePreflightService.ImportCommitSession.class);
        when(session.responses()).thenReturn(responses);
        commits.addLast(session);
    }

    private static HistoryImportRequest activityRequest(
            String sourceId,
            String subject,
            String occurredAt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("when", occurredAt);
        row.put("email", "person@example.com");
        row.put("subject", subject);
        row.put("source", sourceId);
        return new HistoryImportRequest(
            List.of(row),
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("subject", "subject"),
                mapping("source", "sourceId")),
            null,
            null);
    }

    private static HistoryImportRequest noteRequest(
            String sourceId,
            String content,
            String occurredAt) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("when", occurredAt);
        row.put("email", "person@example.com");
        row.put("content", content);
        row.put("source", sourceId);
        return new HistoryImportRequest(
            List.of(row),
            List.of(
                mapping("when", "occurredAt"),
                mapping("email", "participantEmail"),
                mapping("content", "content"),
                mapping("source", "sourceId")),
            null,
            null);
    }

    private static HistoryImportRequest taskRequest(
            String sourceId,
            String description,
            String occurredAt,
            String completed) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("when", occurredAt);
        row.put("email", "person@example.com");
        row.put("description", description);
        row.put("source", sourceId);
        row.put("due", "2026-04-05");
        row.put("completed", completed);
        return new HistoryImportRequest(
            List.of(row),
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

    private static HistoryImportColumnMapping mapping(
            String column,
            String field) {
        return new HistoryImportColumnMapping(column, field);
    }

    private static DuplicatePreflightResponse strongResponse(int personId) {
        return response(List.of(
            candidate(personId, true, DuplicateMatchStrength.STRONG)), false);
    }

    private static DuplicatePreflightResponse response(
            List<DuplicateCandidateDto> candidates,
            boolean truncated) {
        return new DuplicatePreflightResponse(
            "person", candidates, truncated, "review-token");
    }

    private static DuplicateCandidateDto candidate(
            int id,
            boolean owned,
            DuplicateMatchStrength strength) {
        return new DuplicateCandidateDto(
            id,
            "person",
            "Person " + id,
            null,
            null,
            null,
            null,
            owned,
            strength,
            List.of());
    }

    private static Person person(int id, int workspaceId) {
        Person person = new Person();
        person.setId(id);
        person.setWorkspaceId(workspaceId);
        person.setName("Person " + id);
        return person;
    }

    private static HistoryImportProvenance provenance(HistoryImportWrite write) {
        return provenance(0, write);
    }

    private static HistoryImportProvenance provenance(
            int entityId,
            HistoryImportWrite write) {
        HistoryImportProvenance provenance = new HistoryImportProvenance();
        provenance.setEntityId(entityId);
        provenance.setHistoryImportKey(write.getHistoryImportKey());
        provenance.setHistoryPayloadHash(write.getHistoryPayloadHash());
        return provenance;
    }

    private static <T> T inReadCommitted(java.util.function.Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            Connection.TRANSACTION_READ_COMMITTED);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}

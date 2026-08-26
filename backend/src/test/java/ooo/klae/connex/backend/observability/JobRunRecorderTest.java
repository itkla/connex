package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import ooo.klae.connex.backend.beans.JobRun;
import ooo.klae.connex.backend.mappers.JobRunMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JobRunRecorderTest {

    /**
     * Every declared job-name constant must be admitted by the allowlist the recorder validates
     * against. The two lists live forty lines apart in one file and drifted once — the Wave-5
     * schedulers logged an IllegalArgumentException warning on every sweep because their
     * constants existed while the allowlist entries did not.
     */
    @org.junit.jupiter.api.Test
    void everyDeclaredJobNameConstantIsAdmittedByTheAllowlist() throws Exception {
        java.lang.reflect.Field allowlistField = JobRunRecorder.class.getDeclaredField("JOB_NAMES");
        allowlistField.setAccessible(true);
        java.util.Set<?> allowlist = (java.util.Set<?>) allowlistField.get(null);
        for (java.lang.reflect.Field field : JobRunRecorder.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isPublic(field.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                String constant = (String) field.get(null);
                org.junit.jupiter.api.Assertions.assertTrue(allowlist.contains(constant),
                        "Job-name constant '" + constant + "' is missing from JOB_NAMES");
            }
        }
    }

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);

    private JobRunMapper mapper;
    private ObjectMapper objectMapper;
    private JobRunRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(JobRunMapper.class);
        objectMapper = new ObjectMapper();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        recorder = new JobRunRecorder(mapper, objectMapper, CLOCK, transactionManager);
    }

    @Test
    void mapperFailureNeverEscapesOrPersistsExceptionText() {
        String sentinel = "credential-sentinel-must-not-enter-json";
        AtomicReference<JobRun> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            throw new IllegalStateException(sentinel);
        }).when(mapper).insert(any(JobRun.class));

        assertDoesNotThrow(() -> recorder.record(
                JobRunRecorder.REPORT_DELIVERY,
                17,
                JobRunStatus.FAILED,
                JobRunDetail.started(CLOCK, Map.of("phase", "delivery_failed"))));

        assertFalse(inserted.get().getDetail().contains(sentinel));
    }

    @Test
    void everySuccessfulInsertPrunesThePartitionToFiftyRows() {
        recorder.record(
                JobRunRecorder.NOTIFICATION_RECONCILIATION,
                8,
                JobRunStatus.SUCCEEDED,
                JobRunDetail.started(CLOCK));

        verify(mapper).deleteBeyondRetention(
                JobRunRecorder.NOTIFICATION_RECONCILIATION, 8, "succeeded", 50);
    }

    @Test
    void detailDropsUnknownKeysAndNonPrimitiveValues() throws Exception {
        ArgumentCaptor<JobRun> inserted = ArgumentCaptor.forClass(JobRun.class);

        recorder.record(
                JobRunRecorder.REPORT_DELIVERY,
                9,
                JobRunStatus.SUCCEEDED,
                JobRunDetail.started(CLOCK, Map.of(
                        "phase", "queued",
                        "scheduleId", 42,
                        "recipientCount", 3,
                        "unknown", "secret",
                        "snapshotId", List.of(77))));

        verify(mapper).insert(inserted.capture());
        JsonNode detail = objectMapper.readTree(inserted.getValue().getDetail());
        assertTrue(detail.has("phase"));
        assertTrue(detail.has("scheduleId"));
        assertTrue(detail.has("recipientCount"));
        assertFalse(detail.has("unknown"));
        assertFalse(detail.has("snapshotId"));
    }

    @Test
    void nullWorkspaceRowsCarryOnlyPhaseAndNoCounts() throws Exception {
        ArgumentCaptor<JobRun> inserted = ArgumentCaptor.forClass(JobRun.class);

        recorder.record(
                JobRunRecorder.PROVIDER_CAPTURE,
                null,
                JobRunStatus.SUCCEEDED,
                JobRunDetail.started(CLOCK, Map.of(
                        "phase", "catalog_sweep",
                        "attemptedCount", 88,
                        "failedCount", 4)));

        verify(mapper).insert(inserted.capture());
        JsonNode detail = objectMapper.readTree(inserted.getValue().getDetail());
        assertTrue(detail.has("phase"));
        assertFalse(detail.has("attemptedCount"));
        assertFalse(detail.has("failedCount"));
    }

    @Test
    void emptySanitizedDetailIsStoredAsNull() {
        ArgumentCaptor<JobRun> inserted = ArgumentCaptor.forClass(JobRun.class);

        recorder.record(
                JobRunRecorder.RULE_SCHEDULER,
                4,
                JobRunStatus.SKIPPED,
                JobRunDetail.started(CLOCK, Map.of("exception", "hidden")));

        verify(mapper).insert(inserted.capture());
        assertNull(inserted.getValue().getDetail());
    }
}

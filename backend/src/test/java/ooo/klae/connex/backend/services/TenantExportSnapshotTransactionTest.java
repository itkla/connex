package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import tools.jackson.databind.json.JsonMapper;

class TenantExportSnapshotTransactionTest {

    @Test
    void captureUsesOneIndependentReadOnlyRepeatableReadTransaction() throws Exception {
        Method capture = TenantExportSnapshotTransaction.class.getMethod(
            "capture",
            int.class,
            ZipOutputStream.class,
            Path.class,
            TenantExportExecution.class);
        Transactional transactional = capture.getAnnotation(Transactional.class);

        assertTrue(transactional.readOnly());
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
        assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
    }

    @Test
    void missionRowsRequireSupportedContinuationAndErasedTombstones() {
        var transaction = new TenantExportSnapshotTransaction(mock(TenantLifecycleMapper.class),
                JsonMapper.builder().build(), mock(ClientErrorService.class), new TenantLifecycleProperties());
        var run = new ooo.klae.connex.backend.beans.AiAgentRun();
        run.setId(31);
        run.setWorkspaceId(7);
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(2);
        run.setPrivacyGeneration(1);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":41}");
        run.setScopeFingerprint(new byte[32]);
        new ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec().initialize(run);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 31);
        row.put("workspace_id", 7);
        row.put("mission_type", run.getMissionType());
        row.put("definition_version", 1);
        row.put("runtime_contract_version", 2);
        row.put("privacy_generation", 1L);
        row.put("scope_fingerprint", run.getScopeFingerprint());
        row.put("scope_json", run.getScopeJson());
        row.put("plan_schema_version", 1);
        row.put("plan_revision", 1L);
        row.put("plan_json", run.getPlanJson());
        row.put("checkpoint_schema_version", 1);
        row.put("checkpoint_revision", 1L);
        row.put("checkpoint_json", run.getCheckpointJson());
        row.put("memory_schema_version", 1);
        row.put("memory_json", run.getMemoryJson());
        row.put("last_committed_segment_no", 0);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
        for (String column : List.of("definition_version", "runtime_contract_version", "plan_schema_version",
                "checkpoint_schema_version", "memory_schema_version")) {
            Object previous = row.put(column, 99);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
            row.put(column, previous);
        }
        row.put("checkpoint_json", "malformed content");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
        row.clear();
        row.put("purged_at", java.time.LocalDateTime.now());
        row.put("plan_revision", 0L);
        row.put("checkpoint_revision", 0L);
        row.put("last_committed_segment_no", 0);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
        for (String column : List.of("plan_schema_version", "plan_json", "checkpoint_schema_version", "checkpoint_json",
                "memory_schema_version", "memory_json")) {
            row.put(column, "residual");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
            row.remove(column);
        }
        for (String column : List.of("plan_revision", "checkpoint_revision", "last_committed_segment_no")) {
            row.put(column, 1);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(transaction, "validateMission", row));
            row.put(column, 0);
        }
    }

    @Test
    void captureClosesEveryTableAndObjectCursorBeforeReturning() throws Exception {
        TenantLifecycleMapper mapper = mock(TenantLifecycleMapper.class);
        ClientErrorService clientErrorService = mock(ClientErrorService.class);
        AtomicInteger tableCloses = new AtomicInteger();
        AtomicInteger objectCloses = new AtomicInteger();
        when(mapper.streamRows(anyInt(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
            new EmptyCursor<Map<String, Object>>(tableCloses));
        when(mapper.streamActiveObjectReferences(anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
            new EmptyCursor<ActiveObjectReference>(objectCloses));
        TenantExportSnapshotTransaction transaction =
            new TenantExportSnapshotTransaction(
                mapper,
                JsonMapper.builder().build(),
                clientErrorService,
                new TenantLifecycleProperties());
        Path spool = Files.createTempFile("tenant-export-snapshot-test-", ".objects");
        ScheduledThreadPoolExecutor deadlineExecutor =
            new ScheduledThreadPoolExecutor(1);
        deadlineExecutor.setRemoveOnCancelPolicy(true);
        ThreadPoolExecutor cancellationExecutor = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(4));
        cancellationExecutor.prestartAllCoreThreads();
        TenantExportExecution execution = new TenantExportExecution(
            Duration.ofSeconds(5),
            cancellationExecutor,
            failure -> failure);
        execution.bindOrganizationGeneration(1L);
        execution.armDeadline(deadlineExecutor);
        execution.begin();

        try (ZipOutputStream zip = new ZipOutputStream(new ByteArrayOutputStream())) {
            TenantExportSnapshotTransaction.Snapshot snapshot =
                transaction.capture(7, zip, spool, execution);

            assertTrue(snapshot.tables().isEmpty());
            assertEquals(0, snapshot.objectCount());
            assertEquals(TenantLifecycleRegistry.declarations().size(), tableCloses.get());
            assertEquals(1, objectCloses.get());
        } finally {
            execution.writerFinished(null);
            deadlineExecutor.shutdownNow();
            cancellationExecutor.shutdownNow();
            Files.deleteIfExists(spool);
        }
    }

    @Test
    void captureExportsTheSafeRegisteredClientErrorProjection() throws Exception {
        TenantLifecycleMapper mapper = mock(TenantLifecycleMapper.class);
        when(mapper.streamRows(anyInt(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
            new EmptyCursor<Map<String, Object>>(new AtomicInteger()));
        when(mapper.streamActiveObjectReferences(anyInt(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
            new EmptyCursor<ActiveObjectReference>(new AtomicInteger()));
        ClientErrorService clientErrorService = mock(ClientErrorService.class);
        when(clientErrorService.workspaceExportPage(7, 0, 500)).thenReturn(List.of(
            new ClientErrorSupportRowDto(
                71L,
                7,
                "disclosure-hmac",
                "/records/contacts/{id}",
                java.time.Instant.parse("2026-08-01T00:00:00Z"))));
        TenantExportSnapshotTransaction transaction = new TenantExportSnapshotTransaction(
            mapper,
            JsonMapper.builder().build(),
            clientErrorService,
            new TenantLifecycleProperties());
        Path spool = Files.createTempFile("tenant-export-control-test-", ".objects");
        ScheduledThreadPoolExecutor deadlineExecutor = new ScheduledThreadPoolExecutor(1);
        ThreadPoolExecutor cancellationExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<>(1));
        TenantExportExecution execution = new TenantExportExecution(
            Duration.ofSeconds(5), cancellationExecutor, failure -> failure);
        execution.bindOrganizationGeneration(1L);
        execution.armDeadline(deadlineExecutor);
        execution.begin();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            TenantExportSnapshotTransaction.Snapshot snapshot =
                transaction.capture(7, zip, spool, execution);

            assertEquals(
                List.of("client_error"),
                snapshot.tables().stream().map(value -> value.name()).toList());
        } finally {
            execution.writerFinished(null);
            deadlineExecutor.shutdownNow();
            cancellationExecutor.shutdownNow();
            Files.deleteIfExists(spool);
        }
        String clientErrors = null;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("data/client_error.jsonl".equals(entry.getName())) {
                    clientErrors = new String(
                        zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(clientErrors != null && clientErrors.contains("disclosure-hmac"));
        assertFalse(clientErrors.contains("digest"));
    }

    private static final class EmptyCursor<T> implements Cursor<T> {
        private final AtomicInteger closes;

        private EmptyCursor(AtomicInteger closes) {
            this.closes = closes;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean isConsumed() {
            return true;
        }

        @Override
        public int getCurrentIndex() {
            return -1;
        }

        @Override
        public Iterator<T> iterator() {
            return List.<T>of().iterator();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}

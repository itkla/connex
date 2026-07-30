package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.zip.ZipOutputStream;

import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
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
    void captureClosesEveryTableAndObjectCursorBeforeReturning() throws Exception {
        TenantLifecycleMapper mapper = mock(TenantLifecycleMapper.class);
        AtomicInteger tableCloses = new AtomicInteger();
        AtomicInteger objectCloses = new AtomicInteger();
        when(mapper.streamRows(anyInt(), any())).thenAnswer(invocation ->
            new EmptyCursor<Map<String, Object>>(tableCloses));
        when(mapper.streamActiveObjectReferences(anyInt())).thenAnswer(invocation ->
            new EmptyCursor<ActiveObjectReference>(objectCloses));
        TenantExportSnapshotTransaction transaction =
            new TenantExportSnapshotTransaction(mapper, JsonMapper.builder().build());
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

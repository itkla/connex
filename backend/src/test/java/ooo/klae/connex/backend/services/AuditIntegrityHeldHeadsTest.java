package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.AuditIntegrityHead;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.SecuritySignalMetrics;

/**
 * Pins how {@link AuditIntegrityService} remembers which integrity heads the current transaction
 * holds, which is what lets a failure audit wait for its holder instead of waiting on itself (#1879).
 */
class AuditIntegrityHeldHeadsTest {
    private final AuditIntegrityMapper integrityMapper = mock(AuditIntegrityMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private AuditIntegrityService service;

    @BeforeEach
    void setUp() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-audit-integrity-hmac-secret-change-me");
        service = new AuditIntegrityService(
                mock(AuditLogMapper.class),
                integrityMapper,
                mock(UserMapper.class),
                workspaceMapper,
                organizationMapper,
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
                mock(SecuritySignalMetrics.class));
        lenient().when(workspaceMapper.lockWorkspaceForShare(anyInt())).thenAnswer(call -> call.getArgument(0));
        lenient().when(organizationMapper.lockByIdForShare(anyInt())).thenAnswer(call -> call.getArgument(0));
        lenient().when(integrityMapper.lockHead(anyString(), anyInt())).thenAnswer(call -> {
            AuditIntegrityHead head = new AuditIntegrityHead();
            head.setScopeType(call.getArgument(0));
            head.setScopeId(call.getArgument(1));
            head.setNextChainIndex(1);
            head.setCurrentHash("0".repeat(64));
            return head;
        });
        lenient().when(integrityMapper.advanceHead(anyString(), anyInt(), anyLong(), anyLong(), anyString()))
                .thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        for (Object key : List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet())) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
        }
    }

    @Test
    void appendRecordsOnlyTheHeadItLocked() {
        TransactionSynchronizationManager.initSynchronization();

        service.append(workspaceEntry(7));

        assertTrue(service.holdsHead(workspaceEntry(7)));
        assertFalse(service.holdsHead(workspaceEntry(8)));
        assertFalse(service.holdsHead(organizationEntry(7)));
    }

    /**
     * {@code ensureHead}'s upsert already holds the row's exclusive lock, and InnoDB keeps it even if
     * the append's savepoint rolls back, so the head must read as held after a later step fails.
     */
    @Test
    void aHeadStaysHeldWhenTheChainStepFailsAfterTheLock() {
        TransactionSynchronizationManager.initSynchronization();
        when(integrityMapper.lockHead("workspace", 7)).thenThrow(new IllegalStateException("lock wait"));

        assertThrows(IllegalStateException.class, () -> service.append(workspaceEntry(7)));

        assertTrue(service.holdsHead(workspaceEntry(7)));
    }

    /**
     * An inner {@code REQUIRES_NEW} transaction must neither see the outer's heads as its own nor
     * leave its heads behind in the outer's record once it has committed and released them.
     */
    @Test
    void suspendingTheHolderHidesItsHeadsAndResumingRestoresThem() {
        TransactionSynchronizationManager.initSynchronization();
        service.append(workspaceEntry(7));
        TransactionSynchronization holder = onlySynchronization();

        holder.suspend();
        assertFalse(service.holdsHead(workspaceEntry(7)));

        holder.resume();
        assertTrue(service.holdsHead(workspaceEntry(7)));
    }

    @Test
    void completionReleasesTheRecordOnEitherOutcome() {
        TransactionSynchronizationManager.initSynchronization();
        service.append(workspaceEntry(7));
        TransactionSynchronization holder = onlySynchronization();

        holder.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertFalse(service.holdsHead(workspaceEntry(7)));
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    /** A naive second bind replaces the record and then throws, losing the success audit in between. */
    @Test
    void aSecondAppendInOneTransactionKeepsEveryHead() {
        TransactionSynchronizationManager.initSynchronization();

        service.append(workspaceEntry(7));
        assertDoesNotThrow(() -> service.append(organizationEntry(8)));

        assertTrue(service.holdsHead(workspaceEntry(7)));
        assertTrue(service.holdsHead(organizationEntry(8)));
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }

    /** Without a transaction there is no completion to clear it, so nothing may be bound to the thread. */
    @Test
    void appendOutsideATransactionRecordsNothing() {
        service.append(workspaceEntry(7));

        assertFalse(service.holdsHead(workspaceEntry(7)));
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    private TransactionSynchronization onlySynchronization() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        return synchronizations.get(0);
    }

    private static AuditLog workspaceEntry(int workspaceId) {
        AuditLog entry = new AuditLog();
        entry.setAction("note.create");
        entry.setOutcome("success");
        entry.setWorkspaceId(workspaceId);
        entry.setOrgId(1);
        return entry;
    }

    private static AuditLog organizationEntry(int orgId) {
        AuditLog entry = new AuditLog();
        entry.setAction("organization.update");
        entry.setOutcome("success");
        entry.setOrgId(orgId);
        return entry;
    }
}

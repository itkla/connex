package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Cursor;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Kind;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Reference;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;

/** Tests bounded recovery, failure isolation, and the transaction and absence boundaries. */
class UserAssignmentSweepTest {
    private final PlacementRegistry placements = mock(PlacementRegistry.class);
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final AssignmentSweepMapper mapper = mock(AssignmentSweepMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private UserAccountCatalogOffboardingService service;

    @BeforeEach
    void setUp() {
        service = newService();
        when(placements.activeCatalogs()).thenReturn(Arrays.asList((String) null));
        when(scope.unroutedRead(any())).thenAnswer(call -> call.<ControlPlaneRead<?>>getArgument(0).read());
        when(scope.withCatalog(nullable(String.class), any()))
            .thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(users.findExistingIds(anyList())).thenReturn(List.of());
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
            return new SimpleTransactionStatus();
        });
        when(mapper.lockParent(any(), any())).thenReturn(1L);
    }

    private UserAccountCatalogOffboardingService newService() {
        var result = new UserAccountCatalogOffboardingService(mock(DepartureApprovalGuard.class), placements,
            mock(UserOffboardingService.class),
            mock(ProviderCapturePurgeService.class), scope, transactions, mapper, users, metrics,
            mock(OwnershipOfferAccountCleanupService.class));
        ReflectionTestUtils.setField(result, "pageSize", 2);
        return result;
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void immediateCleanupDispatchesEveryKindInItsOwnCommittedUnit(Kind kind) {
        Reference reference = new Reference(1, 4, 1, 9);
        when(mapper.findWatermark(kind)).thenReturn(reference.cursor());
        when(mapper.findReferencesAnywhere(eq(kind), eq(9), any(), any(), eq(2)))
            .thenReturn(List.of(reference), List.of());

        service.sweepAssignmentsAfterDeletion(9);

        var order = inOrder(mapper, transactions);
        order.verify(mapper).lockParent(kind, reference);
        switch (kind) {
            case COMPANY, DEAL -> order.verify(mapper).clearOwner(kind, reference);
            case PERSON, LIFECYCLE_PASS -> {
                order.verify(mapper).clearOwner(kind, reference);
                order.verify(mapper).clearPersonPassOwners(reference);
            }
            case COLLABORATOR -> order.verify(mapper).removeCollaborator(reference);
            case TASK -> order.verify(mapper).unassignTask(reference);
            case INTERVENTION -> order.verify(mapper).clearInterventionOwner(reference);
            case CAMPAIGN -> order.verify(mapper).clearCampaignOwner(reference);
            case GOAL_OWNER -> order.verify(mapper).deleteOwnedGoal(reference);
            case GOAL_CREATOR -> order.verify(mapper).clearGoalCreator(reference);
        }
        order.verify(transactions).commit(any());
    }

    @Test
    void existingAndReservedAccountsArePreservedAndAmbientTransactionsAreRejected() {
        when(users.findExistingIds(List.of(9))).thenReturn(List.of(9));
        service.sweepAssignmentsAfterDeletion(9);
        verifyNoInteractions(mapper, transactions);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.sweepAssignmentsAfterDeletion(9));
            assertThrows(IllegalStateException.class, service::sweepOrphanAssignments);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failureRollsBackOneRecordContinuesSiblingsAndCatalogsAndCountsBoundedTags() {
        when(placements.activeCatalogs()).thenReturn(Arrays.asList(null, "cnx_second"));
        Reference first = new Reference(1, 4, 1, 9);
        Reference second = new Reference(2, 4, 2, 9);
        when(mapper.findWatermark(Kind.COMPANY)).thenReturn(second.cursor());
        when(mapper.findReferencesAnywhere(eq(Kind.COMPANY), eq(9), any(), any(), eq(2)))
            .thenReturn(List.of(first, second), List.of(), List.of(second), List.of());
        doThrow(new IllegalStateException("private driver content"))
            .when(mapper).clearOwner(Kind.COMPANY, first);

        assertDoesNotThrow(() -> service.sweepAssignmentsAfterDeletion(9));

        verify(transactions).rollback(any());
        verify(transactions, times(2)).commit(any());
        verify(mapper, times(2)).clearOwner(Kind.COMPANY, second);
        assertEquals(1, metrics.get("connex.account.assignment.sweep.failures")
            .tags("mode", "immediate", "phase", "record").counter().count());
    }

    @Test
    void watermarkWrapRevisitsFailureDespiteMoreThanAPageOfNewRowsBetweenRuns() {
        Map<Long, Reference> rows = new TreeMap<>();
        for (long id = 1; id <= 4; id++) {
            rows.put(id, new Reference(id, 4, id, 9));
        }
        when(mapper.findWatermark(Kind.COMPANY)).thenAnswer(call -> rows.get(rows.keySet().stream()
            .mapToLong(Long::longValue).max().orElseThrow()).cursor());
        when(mapper.findReferencesAnywhere(eq(Kind.COMPANY), isNull(), any(), any(), eq(2)))
            .thenAnswer(call -> {
                Cursor cursor = call.getArgument(2);
                Cursor watermark = call.getArgument(3);
                return rows.values().stream().filter(row -> row.id() > cursor.id() && row.id() <= watermark.id())
                    .limit(2).toList();
            });
        List<Long> attempted = new ArrayList<>();
        when(mapper.clearOwner(eq(Kind.COMPANY), any())).thenAnswer(call -> {
            Reference row = call.getArgument(1);
            attempted.add(row.id());
            if (row.id() == 1 && attempted.size() == 1) {
                throw new IllegalStateException("first unit fails");
            }
            rows.remove(row.id());
            return 1;
        });
        for (int run = 0; run < 4; run++) {
            service.sweepOrphanAssignments();
            for (long id = 10 + run * 3; id < 13 + run * 3; id++) {
                rows.put(id, new Reference(id, 4, id, 9));
            }
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 1L, 10L), attempted);
        assertFalse(rows.containsKey(1L));
        service = newService();
        service.sweepOrphanAssignments();
        assertTrue(attempted.contains(11L));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"COMPANY", "GOAL_OWNER", "GOAL_CREATOR"})
    void existenceFailureLeavesRecoveryWorkDiscoverable(Kind kind) {
        Reference row = new Reference(1, 4, 1, 9);
        when(mapper.findWatermark(kind)).thenReturn(row.cursor());
        when(mapper.findReferencesAnywhere(eq(kind), isNull(), any(), any(), eq(2)))
            .thenReturn(List.of(row), List.of(), List.of(row));
        when(users.findExistingIds(anyList())).thenThrow(new IllegalStateException()).thenReturn(List.of());
        service.sweepOrphanAssignments();
        verify(mapper, never()).lockParent(any(), any());
        service.sweepOrphanAssignments();
        service.sweepOrphanAssignments();
        verify(mapper).lockParent(kind, row);
        assertEquals(1, metrics.get("connex.account.assignment.sweep.failures")
            .tags("mode", "periodic", "phase", "existence").counter().count());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void periodicCleanupNeverLocksLiveOrReservedAccounts(Kind kind) {
        Reference orphan = new Reference(1, 4, 1, 9);
        Reference live = new Reference(2, 4, 2, 10);
        when(mapper.findWatermark(kind)).thenReturn(live.cursor());
        when(mapper.findReferencesAnywhere(eq(kind), isNull(), any(), any(), eq(2)))
            .thenReturn(List.of(orphan, live));
        when(users.findExistingIds(List.of(9, 10))).thenReturn(List.of(10));

        service.sweepOrphanAssignments();

        verify(mapper).lockParent(kind, orphan);
        verify(mapper, never()).lockParent(kind, live);
        verify(transactions).commit(any());
    }
}

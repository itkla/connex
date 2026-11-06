package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.support.RecordVisibilityScopes;

/** Write responses must carry the counter after outcome, realized-value and reference writes. */
@ExtendWith(MockitoExtension.class)
class DealMutationVersionTest {
    @Mock private DealMapper mapper;
    @Mock private WorkspaceService workspaces;
    @Mock private AuthService auth;
    @Mock private AuditService audit;
    @Mock private DealOutcomeWriter outcomes;
    @Mock private ReferenceService references;
    @Mock private NotificationChangePublisher notifications;
    @Mock private RuleTriggerPublisher triggers;
    @Mock private DealStageHistoryService history;
    @InjectMocks private DealService service;

    @Test
    void closeReturnsTheFinalStoredRecordAfterAllDependentWrites() {
        Deal before = deal(17, null);
        Deal stored = prepare(before, true);
        RecordVisibilityScope scope = new RecordVisibilityScope(90, java.util.List.of(7, 8, 9));

        Deal response = service.close(41, true, null, new BigDecimal("200"));

        assertSame(stored, response);
        assertEquals(19L, DealDto.from(response).getVersion());
        var order = inOrder(outcomes, references, mapper);
        order.verify(outcomes).write(eq(7), org.mockito.ArgumentMatchers.any(Deal.class), eq(null), eq(new BigDecimal("200")), eq(scope));
        order.verify(references).syncReferences(7, ReferenceService.SOURCE_DEAL, 41, null);
        order.verify(mapper).getDealById(7, 41);
        order.verify(references).hydrateDeals(7, java.util.List.of(stored));
    }

    @Test
    void reopenReturnsTheFinalStoredRecordAfterAllDependentWrites() {
        Deal before = deal(17, true);
        Deal stored = prepare(before, null);
        var permissions = mock(WorkspaceService.LockedPermissionSnapshot.class);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(workspaces.lockAndRequirePermissionsSnapshot(eq(7), anyMap())).thenReturn(permissions);
        when(mapper.getStageOutcome(7, "[7,8,9]", 5)).thenReturn("normal");
        RecordVisibilityScope scope = new RecordVisibilityScope(90, java.util.List.of(7, 8, 9));

        Deal response = service.reopen(41);

        assertSame(stored, response);
        assertEquals(19L, DealDto.from(response).getVersion());
        var order = inOrder(outcomes, references, mapper);
        order.verify(outcomes).writeOutcome(eq(7), org.mockito.ArgumentMatchers.any(Deal.class), eq(true), eq(scope));
        order.verify(references).syncReferences(7, ReferenceService.SOURCE_DEAL, 41, null);
        order.verify(mapper).getDealById(7, 41);
        order.verify(references).hydrateDeals(7, java.util.List.of(stored));
    }

    private Deal prepare(Deal before, Boolean outcome) {
        RecordVisibilityScopes.install(service);
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.getDealByIdForUpdate(7, 41)).thenReturn(before);
        Deal stored = deal(19, outcome);
        when(mapper.getDealById(7, 41)).thenReturn(stored);
        when(references.hydrateDeals(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        return stored;
    }

    private static Deal deal(long version, Boolean outcome) {
        Deal deal = new Deal();
        deal.setId(41);
        deal.setWorkspaceId(7);
        deal.setName("Versioned deal");
        deal.setStageId(5);
        deal.setVersion(version);
        deal.setWon(outcome);
        return deal;
    }
}

package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class AnalyticsMemberScopeGateTest {
    @Mock private DealService dealService;
    @Mock private BulkOperationService bulkOperationService;
    @Mock private DealRiskService dealRiskService;
    @Mock private AiGenerationAdapterService aiGenerationAdapterService;
    @Mock private ActivityService activityService;
    @Mock private TaskService taskService;
    @Mock private WorkspaceService workspaceService;
    @Mock private MemberScopeResolver memberScopeResolver;

    private static final MemberScope MEMBER_SCOPE = new MemberScope(MemberScope.Mode.MEMBERS, null, List.of(3));

    private DealController dealController() {
        return new DealController(dealService, bulkOperationService, dealRiskService, aiGenerationAdapterService,
            workspaceService, memberScopeResolver);
    }

    private ActivityController activityController() {
        return new ActivityController(activityService, workspaceService, memberScopeResolver);
    }

    private TaskController taskController() {
        return new TaskController(taskService, workspaceService, memberScopeResolver);
    }

    private void denyNonManager() {
        when(memberScopeResolver.resolve(any(), any(), anyInt())).thenReturn(MEMBER_SCOPE);
        doThrow(new ForbiddenException("Requires ADMIN role in this workspace"))
            .when(workspaceService).requireRole(WorkspaceService.Role.ADMIN);
    }

    private void allowAllTeam() {
        when(memberScopeResolver.resolve(any(), any(), anyInt())).thenReturn(MemberScope.allTeam());
    }

    @Test
    void dealKpisRejectMemberScopeForNonManager() {
        denyNonManager();
        assertThrows(ForbiddenException.class,
            () -> dealController().getDealKpis(
                null, "90d", "members", List.of(3), null, null, null, null, null));
        verify(dealService, never()).getDealKpis(any(), anyInt(), any());
    }

    @Test
    void dealRiskAnalyticsRejectMemberScopeForNonManager() {
        denyNonManager();
        assertThrows(ForbiddenException.class,
            () -> dealController().getDealRiskAnalytics("members", List.of(3)));
        verify(dealRiskService, never()).analytics(anyInt(), any());
    }

    @Test
    void activityVolumeRejectsMemberScopeForNonManager() {
        denyNonManager();
        assertThrows(ForbiddenException.class,
            () -> activityController().getActivityVolume(
                "90d", "members", List.of(3), null, null, null, null, null));
        verify(activityService, never()).getActivityVolume(anyInt(), any());
    }

    @Test
    void taskSummaryRejectsMemberScopeForNonManager() {
        denyNonManager();
        assertThrows(ForbiddenException.class,
            () -> taskController().getTaskSummary("members", List.of(3)));
        verify(taskService, never()).getTaskSummary(any());
    }

    @Test
    void dealKpisAllowAllTeamWithoutManagerCheck() {
        allowAllTeam();
        dealController().getDealKpis(
            null, "90d", null, null, null, null, null, null, null);
        verify(workspaceService, never()).requireRole(any());
        verify(dealService).getDealKpis(any(), eq(90), any());
    }

    @Test
    void revenueSeriesRejectsMemberScopeForNonManager() {
        denyNonManager();
        assertThrows(ForbiddenException.class,
            () -> dealController().getRevenueSeries(
                "2026-01-01", "2026-01-31", "month", null,
                null, null, "members", List.of(3)));
        verify(dealService, never()).getRevenueSeries(any(), any(), any(), any());
    }

    @Test
    void taskSummaryAllowsAllTeamWithoutManagerCheck() {
        allowAllTeam();
        taskController().getTaskSummary(null, null);
        verify(workspaceService, never()).requireRole(any());
        verify(taskService).getTaskSummary(any());
    }
}

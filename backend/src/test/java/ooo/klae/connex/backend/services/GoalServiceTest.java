package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.ReportGoal;
import ooo.klae.connex.backend.dto.ReportGoalRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.GoalMapper;

/** Unit coverage for report-goal target validation. */
class GoalServiceTest {

    private final GoalMapper goalMapper = mock(GoalMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);

    private GoalService goalService;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getMembers(7)).thenReturn(List.of());
        goalService = new GoalService(goalMapper, workspaceService, authService, auditService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E2147483647", "1E-2147483647", "1E+13"})
    void createRejectsTargetsOutsideDecimalFifteenTwo(String target) {
        assertThrows(BadRequestException.class, () -> goalService.create(request(target)));
        verify(goalMapper, never()).insert(any(ReportGoal.class));
    }

    @Test
    void updateRejectsTargetWhoseExponentOverflowsIntegerDigitCount() {
        ReportGoal existing = new ReportGoal();
        existing.setId(3);
        existing.setWorkspaceId(7);
        when(goalMapper.getGoal(7, 3)).thenReturn(existing);

        assertThrows(BadRequestException.class, () -> goalService.update(3, request("1E2147483647")));
        verify(goalMapper, never()).update(any(ReportGoal.class));
    }

    private static ReportGoalRequest request(String target) {
        return new ReportGoalRequest(
                null, "won_revenue", "month", LocalDate.of(2026, 9, 1), new BigDecimal(target), "USD");
    }
}

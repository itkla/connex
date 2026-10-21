package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.mission.AiAgentRunService;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAgentMissionWorkItemProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private final ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard privacyGuard =
            mock(ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiAgentRunService missions = mock(AiAgentRunService.class);
    private final AiAgentMissionWorkItemProvider provider = new AiAgentMissionWorkItemProvider(
            privacyGuard, runs, missions, mock(WorkspaceService.class));
    private final WorkItemProviderQuery query = new WorkItemProviderQuery(7, 42,
            LocalDate.of(2026, 10, 19), NOW, Set.of(), 25);

    @org.junit.jupiter.api.BeforeEach
    void privacyAuthority() {
        when(privacyGuard.currentGeneration(7)).thenReturn(1L);
    }

    @Test
    void projectsOneItemPerWaitingRoot() {
        when(runs.listRuns(7, 42, null, "waiting_approval", null, 100, 1L)).thenReturn(List.of(run(31), run(32)));
        when(missions.output(31)).thenReturn(output(31, NOW.plusSeconds(60), 2));
        when(missions.output(32)).thenReturn(output(32, NOW.plusSeconds(60), 1));
        var result = provider.load(query);
        assertEquals(2, result.overallTotal());
        assertEquals(2, result.items().size());
        assertEquals(List.of(WorkItemAction.open_context), result.items().getFirst().permittedActions());
        assertEquals("/ask-connex/91?mission=31", result.items().getFirst().context().href());
        assertTrue(result.totalsComplete());
    }

    @Test
    void excludesExpiredStagedWithheldAndNonrequesterRoots() {
        when(runs.listRuns(7, 42, null, "waiting_approval", null, 100, 1L))
                .thenReturn(List.of(run(31), run(32), run(33), run(34)));
        when(missions.output(31)).thenReturn(output(31, NOW, 1));
        when(missions.output(32)).thenReturn(output(32, NOW.plusSeconds(60), 0));
        when(missions.output(33)).thenThrow(new AiAgentGoneException("mission_withheld"));
        when(missions.output(34)).thenThrow(new ooo.klae.connex.backend.exceptions.ResourceNotFoundException("mission"));
        var result = provider.load(query);
        assertEquals(0, result.overallTotal());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void boundedScanReportsIncompleteTotalsHonestly() {
        when(runs.listRuns(eq(7), eq(42), isNull(), eq("waiting_approval"), any(), eq(100), eq(1L)))
                .thenAnswer(invocation -> {
                    Integer before = invocation.getArgument(4);
                    int start = before == null ? 2000 : before;
                    return IntStream.rangeClosed(1, 100).mapToObj(offset -> run(start - offset)).toList();
                });
        when(missions.output(anyInt())).thenThrow(new AiAgentGoneException("mission_withheld"));
        var result = provider.load(query);
        assertEquals(0, result.overallTotal());
        assertFalse(result.totalsComplete());
    }

    @Test
    void refusesBulkDecisionAuthority() {
        assertThrows(BadRequestException.class, () -> provider.execute(31,
                new WorkItemActionCommand(WorkItemAction.approve, "state", null, "approved", null, null)));
    }

    private AiAgentRun run(int id) {
        var run = new AiAgentRun();
        run.setId(id);
        run.setSessionId(91);
        return run;
    }

    private AiAgentOutputDto output(int id, Instant deadline, int pending) {
        return new AiAgentOutputDto(1, id, "7", null,
                new AiAgentOutputDto.ProposalSet(id, "3", true, NOW.minusSeconds(30),
                        NOW.minusSeconds(60), deadline, List.of(), new AiAgentOutputDto.Counts(pending, 0, 0, 0, 0)),
                List.of());
    }
}

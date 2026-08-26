package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatProgressServiceTest {
    private final AiChatMapper chatMapper = mock(AiChatMapper.class);
    private final AiChatProgressService service = new AiChatProgressService(
            chatMapper, JsonMapper.builder().build());

    @Test
    void projectsBoundedSourceMilestonesFromDurableToolCalls() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", 64))
                .thenReturn(List.of(
                        toolCall(1, "search_records", "executed",
                                "{\"records\":[{},{}],\"truncated\":true}"),
                        toolCall(2, "list_activities", "failed", null)));

        assertEquals(
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(1, "records", "complete", 2, true),
                        new AiChatProgressItemDto(2, "activities", "failed", null, false),
                        new AiChatProgressItemDto(65, "answer", "running", null, false)),
                service.project(3, 5, 7, "running"));
    }

    @Test
    void realtimeProjectionRemovesInternalToolAndFailureDetails() {
        AiChatStepFrameDto projected = AiChatProgressService.viewerFrame(
                new AiChatStepFrameDto(
                        3, 5, 7, 2, "step", "find_schedule_conflicts",
                        "failed", "database_timeout", 11, "private text"));

        assertEquals("schedule", projected.tool());
        assertEquals("failed", projected.status());
        assertEquals(11, projected.toolCallId());
        assertNull(projected.reason());
        assertNull(projected.text());

        assertNull(AiChatProgressService.sharedFrame(projected).toolCallId());
    }

    @Test
    void settledConfirmProposalStaysAwaitingApprovalRatherThanReadingAsComplete() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", 64))
                .thenReturn(List.of(toolCall(1, "assign_owner", "proposed", null)));

        assertEquals(
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(1, "actions", "proposed", null, false),
                        new AiChatProgressItemDto(65, "answer", "complete", null, false)),
                service.project(3, 5, 7, "resolved"));
    }

    @Test
    void everySourceCategoryComesFromTheSharedCoverageVocabulary() {
        assertEquals("records", AiChatProgressService.sourceForTool("search_records"));
        assertEquals("deals", AiChatProgressService.sourceForTool("get_deal_brief"));
        assertEquals("schedule", AiChatProgressService.sourceForTool("find_schedule_conflicts"));
        assertEquals("notes", AiChatProgressService.sourceForTool("create_note"));
        assertEquals(
                "activities", AiChatProgressService.sourceForTool("list_scope_activities"));
        assertEquals("metrics", AiChatProgressService.sourceForTool("relationship_metrics"));
        assertEquals("deals", AiChatProgressService.sourceForTool("deal_attention"));
        assertEquals("other", AiChatProgressService.sourceForTool("a_future_tool"));
        assertTrue(AiChatProgressService.PROGRESS_SOURCES.containsAll(
                AiAssistantStepGuard.COVERAGE_SOURCES));
        assertTrue(AiChatProgressService.PROGRESS_SOURCES.contains("scope"));
        assertTrue(AiChatProgressService.PROGRESS_SOURCES.contains("answer"));
        assertEquals(
                AiAssistantStepGuard.COVERAGE_SOURCES.size() + 2,
                AiChatProgressService.PROGRESS_SOURCES.size());
    }

    @Test
    void onlyTheExecutorsOwnTruncationFlagsBoundTheReportedProgress() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", 64))
                .thenReturn(List.of(toolCall(1, "list_activities", "executed",
                        "{\"activities\":[{\"subject\":\"Renewal\",\"truncatedByOwner\":true}]}")));

        assertEquals(
                new AiChatProgressItemDto(1, "activities", "complete", 1, false),
                service.project(3, 5, 7, "resolved").get(1));

        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", 64))
                .thenReturn(List.of(toolCall(1, "list_activities", "executed",
                        "{\"activities\":[{\"notesTruncated\":true}]}")));

        assertEquals(
                new AiChatProgressItemDto(1, "activities", "complete", 1, true),
                service.project(3, 5, 7, "resolved").get(1));
    }

    @Test
    void durableTruncationDowngradesACompleteCoverageClaim() {
        var claimed = new AiAssistantStep.Coverage(
                "complete", "2026-08-21", null, null,
                List.of("records"), List.of(), false);

        var reconciled = AiChatProgressService.reconcileCoverage(
                claimed,
                List.of(new AiChatProgressItemDto(
                        1, "records", "complete", 10, true)),
                new AiAssistantPromptAssembler.ToolBudgetAudit(1, 0, 10, 20));

        assertEquals("partial", reconciled.status());
        assertEquals(List.of("bounded_results"), reconciled.exclusions());
        assertEquals(true, reconciled.truncated());
    }

    private static AiChatToolCall toolCall(
            int step, String name, String status, String resultJson) {
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setToolName(name);
        toolCall.setStatus(status);
        toolCall.setResultJson(resultJson);
        toolCall.setIdempotencyKey("turn-7-step-" + step);
        return toolCall;
    }
}

package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatProgressServiceTest {

    /**
     * The row limit the projection passes, named so a regression is a compile-time-visible change.
     *
     * <p>Every stub below is keyed on it, so lowering the production limit back to the step ceiling
     * leaves every stub unmatched and this whole class red rather than silently projecting a
     * truncated turn.
     */
    private static final int ROW_LIMIT = AiChatAgentLoopService.MAX_TOOL_CALL_ROWS_PER_TURN;

    private final AiChatMapper chatMapper = mock(AiChatMapper.class);
    private final AiChatProgressService service = new AiChatProgressService(
            chatMapper, JsonMapper.builder().build());

    @Test
    void projectsBoundedSourceMilestonesFromDurableToolCalls() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
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

    /**
     * A {@code find_tools} row raises no milestone, because the loop publishes no step frame for
     * it. Projecting one would show its unmapped {@code other} category on reload that no live
     * frame ever raised, so the coverage strip and the reloaded transcript would disagree over a
     * step that read nothing.
     */
    @Test
    void aFindToolsRowRaisesNoMilestoneSoLiveAndSettledCoverageAgree() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(
                        toolCall(1, AiAssistantToolCatalog.FIND_TOOLS, "executed",
                                "{\"loaded\":\"analytics\",\"active\":[\"core\",\"analytics\"]}"),
                        toolCall(2, "aggregate_metric", "executed", "{\"metrics\":[{}]}")));

        assertEquals(
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(2, "metrics", "complete", null, false),
                        new AiChatProgressItemDto(65, "answer", "complete", null, false)),
                service.project(3, 5, 7, "resolved"));
        assertEquals(
                "other",
                AiChatProgressService.sourceForTool(AiAssistantToolCatalog.FIND_TOOLS),
                "sourceForTool stays untouched; the row is skipped, never categorized");
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
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
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
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(toolCall(1, "list_activities", "executed",
                        "{\"activities\":[{\"subject\":\"Renewal\",\"truncatedByOwner\":true}]}")));

        assertEquals(
                new AiChatProgressItemDto(1, "activities", "complete", 1, false),
                service.project(3, 5, 7, "resolved").get(1));

        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(toolCall(1, "list_activities", "executed",
                        "{\"activities\":[{\"notesTruncated\":true}]}")));

        assertEquals(
                new AiChatProgressItemDto(1, "activities", "complete", 1, true),
                service.project(3, 5, 7, "resolved").get(1));
    }


    /**
     * A row whose key names a call ordinal still projects under its own step.
     *
     * <p>The parser is anchored, so an unrecognised key falls through to the {@code HARD_MAX_STEPS}
     * placeholder and sorts a real milestone to the end of the turn, after the synthetic answer
     * milestone. The optional suffix group is what keeps a row written by a call that shared its
     * step from landing there.
     */
    @Test
    void aKeyNamingACallOrdinalStillProjectsUnderItsOwnStep() {
        AiChatToolCall batched = toolCall(2, "list_activities", "executed", "{\"activities\":[]}");
        batched.setIdempotencyKey("turn-7-step-2-call-3");
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(
                        toolCall(1, "search_records", "executed", "{\"records\":[{}]}"),
                        batched));

        assertEquals(
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(1, "records", "complete", 1, false),
                        new AiChatProgressItemDto(2, "activities", "complete", 0, false),
                        new AiChatProgressItemDto(65, "answer", "complete", null, false)),
                service.project(3, 5, 7, "resolved"));
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

    /**
     * The projection reads every row a turn can write, not one row per model step.
     *
     * <p>The limit was the step ceiling while a step wrote exactly one row. A step that may carry
     * several calls breaks that equality, and the failure is silent: every suffixed key still
     * parses and every milestone still looks healthy while the rows past the limit are simply
     * absent. Asserting the argument is the only place that stays honest about it.
     */
    @Test
    void theProjectionReadsEveryRowABatchedTurnCouldHaveWritten() {
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(
                        toolCall(1, "search_records", "executed", "{\"records\":[]}")));

        service.project(3, 5, 7, "resolved");

        verify(chatMapper).listToolCallsByTurn(
                3,
                5,
                "turn-7-step-",
                AiChatAgentLoopService.HARD_MAX_STEPS
                        * AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS);
        assertTrue(
                ROW_LIMIT > AiChatAgentLoopService.HARD_MAX_STEPS,
                "the projection must read past one row per model step");
    }

    /**
     * A key claiming a call position no step could have produced is treated as malformed.
     *
     * <p>The suffix group is unbounded in the pattern, so bounding it here is what keeps a row this
     * loop could never have written from being trusted for its step number and sorted among the
     * real milestones instead of after the answer.
     */
    @Test
    void aKeyNamingAnImpossibleCallOrdinalSortsWithTheMalformedKeys() {
        AiChatToolCall impossible =
                toolCall(2, "list_activities", "executed", "{\"activities\":[]}");
        impossible.setIdempotencyKey("turn-7-step-2-call-"
                + (AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1));
        when(chatMapper.listToolCallsByTurn(3, 5, "turn-7-step-", ROW_LIMIT))
                .thenReturn(List.of(impossible));

        assertEquals(
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(
                                AiChatAgentLoopService.HARD_MAX_STEPS,
                                "activities", "complete", 0, false),
                        new AiChatProgressItemDto(65, "answer", "complete", null, false)),
                service.project(3, 5, 7, "resolved"));
    }
}

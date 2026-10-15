package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.tenant.Permission;

/** Task proposals preserve semantics, authorization, lock order and verified delegate outcomes. */
class AiAssistantTaskWriteToolsTest extends AbstractAiAssistantWriteToolTest {
    private Task task() {
        Task task = new Task();
        task.setId(73);
        task.setDescription("Send the agenda");
        task.setStatus("todo");
        task.setDueDate("2026-10-10");
        User assignee = new User();
        assignee.setId(TURN.userId());
        task.setAssignedTo(assignee);
        when(taskService.getTaskById(73)).thenReturn(task);
        when(taskService.assistantStateVersion(73)).thenReturn(TaskService.assistantStateVersion(task));
        when(taskService.lockTaskForUpdate(73)).thenReturn(task);
        return task;
    }

    @Test
    void completionPinsCanonicalStateAndLocksBoardBeforeRowAndScopeGate() throws Exception {
        Task task = task();
        when(taskService.complete(73)).thenReturn(task);
        AiAssistantWriteToolService service = service();
        var proposal = propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        var stored = objectMapper.readTree(proposal.argumentsJson());
        assertEquals("t1", stored.path("request").path("handle").asString());
        assertEquals(TaskService.assistantStateVersion(task), stored.path("targetVersion").asString());
        verify(taskService, never()).complete(anyInt());
        clearInvocations(taskService);
        var applied = service.approve(TURN.sessionId(), TOOL_CALL_ID);
        InOrder order = inOrder(taskService);
        order.verify(taskService).lockBoardForUpdate();
        order.verify(taskService).lockTaskForUpdate(73);
        order.verify(taskService).getTaskById(73);
        order.verify(taskService).complete(73);
        assertFalse(applied.undoAvailable());
        assertEquals("task", applied.result().path("recordType").asString());
        assertFalse(applied.result().has("taskId"));
    }

    @Test
    void positionAndTimestampDriftDoNotInvalidateRescheduleAndItTakesNoBoard() throws Exception {
        Task task = task();
        when(taskService.reschedule(73, "2026-10-15")).thenReturn(task);
        AiAssistantWriteToolService service = service();
        propose(service, "reschedule_task", "{\"handle\":\"t1\",\"due_date\":\"2026-10-15\"}", "task", 73);
        task.setPosition(99);
        task.setUpdatedAt("2026-10-01 12:00:00");
        clearInvocations(taskService);
        service.approve(TURN.sessionId(), TOOL_CALL_ID);
        InOrder order = inOrder(taskService);
        order.verify(taskService).lockTaskForUpdate(73);
        order.verify(taskService).getTaskById(73);
        order.verify(taskService).reschedule(73, "2026-10-15");
        verify(taskService, never()).lockBoardForUpdate();
        verify(taskService, never()).lockBoardForCreation();
    }

    @Test
    void reassignmentRefusesBeforeDelegateEvenIfTimestampDidNotMove() throws Exception {
        Task task = task();
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        User other = new User();
        other.setId(99);
        task.setAssignedTo(other);
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Assistant proposal target changed", refusal.getMessage());
        verify(taskService, never()).complete(anyInt());
    }

    @Test
    void taskPermissionAndPostLockScopeGateRefuseBeforeApply() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.TASK_UPDATE);
        when(authority.effectiveFor(TURN.userId())).thenReturn(permissions);
        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Requires the TASK_UPDATE permission in this workspace", refusal.getMessage());
        verify(taskService, never()).lockTaskForUpdate(anyInt());
        when(authority.effectiveFor(TURN.userId())).thenReturn(EnumSet.allOf(Permission.class));
        when(taskService.getTaskById(73)).thenThrow(new ResourceNotFoundException("Task unavailable"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verify(taskService, never()).complete(anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete_task", "reschedule_task"})
    void linkedRecordsAreReauthorizedAfterTheTaskLockBeforeEitherDelegate(String tool) throws Exception {
        Task task = task();
        Person person = new Person();
        person.setId(31);
        task.setPerson(person);
        Deal deal = new Deal();
        deal.setId(41);
        task.setDeal(deal);
        when(taskService.assistantStateVersion(73)).thenReturn(TaskService.assistantStateVersion(task));
        AiAssistantWriteToolService service = service();
        String date = "reschedule_task".equals(tool) ? ",\"due_date\":\"2026-10-15\"" : "";
        propose(service, tool, "{\"handle\":\"t1\"" + date + "}", "task", 73);
        clearInvocations(taskService, personService, dealService);
        when(dealService.getDealById(41)).thenThrow(new ResourceNotFoundException("Deal unavailable"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        InOrder order = inOrder(taskService, personService, dealService);
        order.verify(taskService).lockTaskForUpdate(73);
        order.verify(taskService).getTaskById(73);
        order.verify(personService).getPersonById(31);
        order.verify(dealService).getDealById(41);
        verify(taskService, never()).complete(anyInt());
        verify(taskService, never()).reschedule(anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aReplayedProposalKeepsItsOriginalVersionWithoutReadingTheChangedTask() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        var first = propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        clearInvocations(taskService);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.registerTask(73);
        var replay = service.prepareReplay("complete_task", objectMapper.readTree("{\"handle\":\"t1\"}"),
                resources, TURN.restrictionEpoch(), first.argumentsJson());
        assertEquals(first.argumentsJson(), replay.argumentsJson());
        verifyNoInteractions(taskService);
    }

    @Test
    void invalidCalendarDatesNeverCreateAnApprovableProposal() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        AiAssistantLoopException refusal = assertThrows(AiAssistantLoopException.class,
                () -> propose(service, "reschedule_task",
                        "{\"handle\":\"t1\",\"due_date\":\"2026-02-30\"}", "task", 73));
        assertEquals("invalid_tool_arguments", refusal.detailReason());
        verify(taskService, never()).reschedule(anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void taskReadBackRecordsDivergenceWithoutLeakingIdentifiersToTheOutcome() throws Exception {
        task();
        Task wrong = new Task();
        wrong.setId(74);
        wrong.setDescription("Send the agenda");
        wrong.setCompleted(true);
        when(taskService.complete(73)).thenReturn(wrong);
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        var applied = service.approve(TURN.sessionId(), TOOL_CALL_ID);
        assertTrue(objectMapper.readTree(capturedExecutedResult()).has("verification"));
        assertFalse(applied.result().has("verification"));
        assertFalse(applied.result().has("taskId"));
    }

    @ParameterizedTest
    @CsvSource({"complete_task,false", "complete_task,true", "reschedule_task,false", "reschedule_task,true"})
    void storedDescriptionMemberCandidatesMaskFreshAndReplayedOutcomesAndRejectGuesses(
            String tool, boolean nativeProtocol) throws Exception {
        Task task = task();
        String description = "Follow up with [Former Member](user:21) and [Pending Member](user:22).";
        task.setDescription(description);
        when(taskService.assistantStateVersion(73)).thenReturn(TaskService.assistantStateVersion(task));
        when(taskService.complete(73)).thenReturn(task);
        when(taskService.reschedule(73, "2026-10-15")).thenReturn(task);
        AiAssistantWriteToolService service = service();
        String date = "reschedule_task".equals(tool) ? ",\"due_date\":\"2026-10-15\"" : "";
        var write = propose(service, tool, "{\"handle\":\"t1\"" + date + "}", "task", 73);
        service.approve(TURN.sessionId(), TOOL_CALL_ID);
        String stored = capturedExecutedResult();
        assertTrue(stored.contains("Former Member"));
        assertTrue(stored.contains("Pending Member"));
        assertFalse(stored.contains("{{M"));
        var assembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var budget = new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000);
        var support = new AiAssistantMaskingTestSupport(TURN.workspaceId(), 11);
        try {
            for (int replay = 0; replay < 2; replay++) {
                var result = service.proposalResult(write, new AiAssistantToolProposal(
                        TOOL_CALL_ID, "executed", stored, true));
                assertEquals(List.of(new AiAssistantToolResult.Identifier("member", "Former Member", "/outcome/description"),
                        new AiAssistantToolResult.Identifier("member", "Pending Member", "/outcome/description")), result.identifiers());
                var context = new ooo.klae.connex.backend.ai.masking.MaskingContext();
                context.registerMemberCandidate("Renamed Member");
                result.identifiers().forEach(identifier -> identifier.seed(context));
                assertEquals(0, context.tokenBindings().size());
                var resources = new AiChatResourceRegistry(context);
                var prompt = nativeProtocol
                        ? assembler.assembleNative(List.of(), result, List.of(), context, resources,
                                List.of(), budget, AiAssistantToolCatalog.ALL)
                        : assembler.assemble(List.of(), result, List.of(), context, resources,
                                AiAssistantToolCatalog.ALL);
                String answer = "{\"text\":\"{{M1}} and {{M2}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
                String output = nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}";
                var capture = support.captureAssistantPayload(prompt, context, objectMapper, nativeProtocol,
                        List.of(), output, false, null);
                String payload = objectMapper.writeValueAsString(capture.request().messages());
                assertFalse(payload.contains("Former Member"));
                assertFalse(payload.contains("Pending Member"));
                assertFalse(payload.contains("Renamed Member"));
                assertTrue(payload.contains("{{M1}}"));
                assertTrue(payload.contains("{{M2}}"));
                assertEquals(0, capture.warnings());
                assertEquals("Former Member and Pending Member",
                        ooo.klae.connex.backend.ai.masking.Demasker.demask("{{M1}} and {{M2}}", context).text());
                String guessed = "{\"text\":\"{{M3}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
                var refused = support.captureAssistantPayload(prompt, context, objectMapper, nativeProtocol, List.of(),
                        nativeProtocol ? guessed : "{\"tool\":null,\"final\":" + guessed + "}", false, null);
                assertEquals(1, refused.warnings());
                var demaskedGuess = ooo.klae.connex.backend.ai.masking.Demasker.demask("{{M3}}", context);
                assertEquals(1, demaskedGuess.warnings());
                for (String label : List.of("Former Member", "Pending Member", "Renamed Member")) {
                    assertFalse(demaskedGuess.text().contains(label));
                }
                assertEquals(2, context.tokenBindings().size());
                assertEquals(description, objectMapper.readTree(stored).path("outcome").path("description").asString());
            }
            verify(taskService, times("complete_task".equals(tool) ? 1 : 0)).complete(73);
            verify(taskService, times("reschedule_task".equals(tool) ? 1 : 0)).reschedule(73, "2026-10-15");
        } finally {
            support.releaseFixtureMocks();
        }
    }

}

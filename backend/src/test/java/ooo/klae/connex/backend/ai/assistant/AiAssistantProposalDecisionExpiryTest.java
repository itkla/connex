package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.tenant.Permission;

class AiAssistantProposalDecisionExpiryTest extends AbstractAiAssistantWriteToolTest {
    @Test
    void undoWindowIsIndependentOfReviewWindow() throws Exception {
        createdTasksGetId74();
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Follow up\"}", "person", 31);
        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });
        storedToolCall.setCreatedAt("2026-01-01 15:00:00.000000");
        if (!(objectMapper.readTree(storedToolCall.getResultJson())
                instanceof tools.jackson.databind.node.ObjectNode result)) {
            throw new AssertionError("Created task must carry its execution result");
        }
        if (!(result.get("undo") instanceof tools.jackson.databind.node.ObjectNode undo)) {
            throw new AssertionError("Created task must carry its inverse");
        }
        undo.put("expiresAt", CLOCK.instant().toString());
        storedToolCall.setResultJson(objectMapper.writeValueAsString(result));
        assertEquals("undone", service.undo(TURN.sessionId(), TOOL_CALL_ID).reviewState());
        assertEquals("undone", service.undo(TURN.sessionId(), TOOL_CALL_ID).status());
        verify(taskService).deleteIf(eq(74), any());
    }

    @Test
    void frameworkRejectsSuccessfulToolsThatSkipDelegateAdmission() throws Exception {
        AiAssistantCreateTaskWriteTool skipping = new AiAssistantCreateTaskWriteTool(
                taskService, dateResolver, objectMapper) {
            @Override
            public Outcome apply(Execution execution) {
                return new Outcome(java.util.Map.of("status", "executed"), null,
                        ReadBack.structural("personId", 31));
            }
        };
        AiAssistantWriteToolService service = service(java.util.List.of(skipping, stageTool()));
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Follow up\"}", "person", 31);
        assertThrows(IllegalStateException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void legacyKnownIdApplyExpiresWithoutMutation() throws Exception {
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"unassigned\"}", "person", 31);
        storedToolCall.setCreatedAt("2026-03-03 15:00:00.000000");
        assertThrows(AiAssistantProposalExpiredException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("proposed", storedToolCall.getStatus());
        verify(personService, never()).updateOwner(anyInt(), any());
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void legacyKnownIdDiscardExpiresWithoutRejection() throws Exception {
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"unassigned\"}", "person", 31);
        storedToolCall.setCreatedAt("2026-03-03 15:00:00.000000");
        assertThrows(AiAssistantProposalExpiredException.class,
                () -> service.reject(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("proposed", storedToolCall.getStatus());
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void discardDoesNotAcquireDomainAuthority() throws Exception {
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"unassigned\"}", "person", 31);
        when(authority.effectiveFor(TURN.userId())).thenReturn(Set.of(Permission.AI_USE));
        when(personService.getPersonById(31)).thenThrow(new AssertionError("Discard must not resolve its target"));
        assertEquals("discarded", service.reject(TURN.sessionId(), TOOL_CALL_ID).reviewState());
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt(), org.mockito.ArgumentMatchers.anyString());
        verify(chatMapper).updateToolCall(eq(TURN.workspaceId()), eq(TURN.userMessageId()),
                eq(TOOL_CALL_ID), eq("rejected"), any(), eq(TURN.userId()));
    }

    @Test
    void strandedAutoExpiresWithoutManualApproval() throws Exception {
        createdTasksGetId74();
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Follow up\"}", "person", 31);
        storedToolCall.setCreatedAt("2026-03-03 15:00:00.000000");
        assertThrows(ConflictException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertThrows(ConflictException.class, () -> service.reject(TURN.sessionId(), TOOL_CALL_ID));
        assertThrows(AiAssistantProposalExpiredException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));
        verify(taskService, never()).create(any());
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }
}

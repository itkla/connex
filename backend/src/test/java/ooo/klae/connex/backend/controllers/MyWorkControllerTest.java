package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemActionOutcome;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemAvailability;
import ooo.klae.connex.backend.dto.WorkItemPageDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.work.WorkItemService;

@ExtendWith(MockitoExtension.class)
class MyWorkControllerTest {
    private static final String VERSION = "a".repeat(64);
    private static final Instant AS_OF = Instant.parse("2026-08-30T12:00:00Z");

    @Mock private WorkItemService workItemService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MyWorkController(workItemService))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
    }

    @Test
    void listBindsRepeatedFiltersAndReturnsTheEnvelope() throws Exception {
        when(workItemService.getPage(
                List.of("task", "notification"),
                List.of("critical", "high"),
                2,
                50))
            .thenReturn(new WorkItemPageDto(
                List.of(), 2, 50, 7, 9, false, true, false,
                WorkItemAvailability.partial, List.of(), AS_OF));

        mockMvc.perform(get("/api/my-work")
                .param("source", "task", "notification")
                .param("urgency", "critical", "high")
                .param("page", "2")
                .param("size", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.knownMatchingTotal").value(7))
            .andExpect(jsonPath("$.totalsComplete").value(false))
            .andExpect(jsonPath("$.availability").value("partial"));
    }

    @Test
    void completeRequiresAndParsesCurrentStrongVersion() throws Exception {
        when(workItemService.completeTask(4, VERSION)).thenReturn(response(WorkItemSource.task, 4));

        mockMvc.perform(post("/api/my-work/tasks/4/complete")
                .header("If-Match", "\"" + VERSION + "\""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("task"))
            .andExpect(jsonPath("$.outcome").value("applied"));

        verify(workItemService).completeTask(4, VERSION);

        mockMvc.perform(post("/api/my-work/tasks/4/complete"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void snoozeValidatesBodyAndDelegatesExpectedVersion() throws Exception {
        when(workItemService.snoozeNotification(eq(11), any(SnoozeRequest.class), eq(VERSION)))
            .thenReturn(response(WorkItemSource.notification, 11));

        mockMvc.perform(post("/api/my-work/notifications/11/snooze")
                .header("If-Match", VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":4}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/my-work/notifications/11/snooze")
                .header("If-Match", VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void decisionRequiresStepAndBoundedComment() throws Exception {
        when(workItemService.decideApproval(8, 21, "approved", "ok", VERSION))
            .thenReturn(response(WorkItemSource.document_approval, 8));

        mockMvc.perform(post("/api/my-work/document-approvals/8/decision")
                .header("If-Match", VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stepId\":21,\"decision\":\"approved\",\"comment\":\"ok\"}"))
            .andExpect(status().isOk());

        verify(workItemService).decideApproval(8, 21, "approved", "ok", VERSION);

        mockMvc.perform(post("/api/my-work/document-approvals/8/decision")
                .header("If-Match", VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"approved\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void standardForbiddenNotFoundAndStaleShapesArePreserved() throws Exception {
        when(workItemService.completeTask(1, VERSION))
            .thenThrow(new ForbiddenException("denied"));
        when(workItemService.dismissNotification(2, VERSION))
            .thenThrow(new ResourceNotFoundException("missing"));
        when(workItemService.completeTask(3, VERSION))
            .thenThrow(new ConflictException("stale"));

        mockMvc.perform(post("/api/my-work/tasks/1/complete").header("If-Match", VERSION))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/my-work/notifications/2/dismiss").header("If-Match", VERSION))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/my-work/tasks/3/complete").header("If-Match", VERSION))
            .andExpect(status().isConflict());
    }

    @Test
    void malformedVersionIsRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/api/my-work/tasks/4/complete")
                .header("If-Match", "W/\"" + VERSION + "\""))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(workItemService);
    }

    private static WorkItemActionResponse response(WorkItemSource source, int sourceId) {
        return new WorkItemActionResponse(
            source, sourceId, WorkItemActionOutcome.applied, true, null, AS_OF);
    }
}

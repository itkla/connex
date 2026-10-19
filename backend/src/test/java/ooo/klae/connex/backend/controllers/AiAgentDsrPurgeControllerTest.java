package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.mission.AiAgentDsrPurgeService;
import ooo.klae.connex.backend.ai.mission.AiAgentPurgeAcceptance;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeDto;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Servlet DTO, delegation, status, and body boundaries; the real security chain has separate HTTP coverage. */
@ExtendWith(MockitoExtension.class)
class AiAgentDsrPurgeControllerTest {
    private static final int ORGANIZATION = 5;
    private static final int ACTOR = 7;
    private static final long REQUEST = 2147483648L;
    private static final String KEY = "2ec8b877-06f4-44a1-b315-e8ad20a61a8c";
    private static final String OPERATION = "692d354f-7365-4857-8335-c1ecfa977bfb";
    private static final String SNAPSHOT = "dsr1." + "A".repeat(43);
    private static final String BASE = "/api/orgs/5/data-subject-requests/2147483648";
    private static final String INITIATE = BASE + "/mission-holdings-purge";
    private static final String PROGRESS = BASE + "/mission-holdings-purges/" + OPERATION;
    private static final String BODY = """
            {"idempotencyKey":"%s","snapshotToken":"%s",
            "scopeAcknowledgement":"organization_mission_holdings_v1",
            "cutoffPolicyAcknowledgement":"contents_at_each_workspace_first_invalidation_v1"}
            """.formatted(KEY, SNAPSHOT);
    @Mock private AiAgentDsrPurgeService service;
    @Mock private AuthService auth;
    @Mock private ErrorReporter errors;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AiAgentDsrPurgeController(service, auth))
                .setControllerAdvice(new GlobalExceptionHandler(errors, new TenantContext()))
                .addFilter(new ApiRequestBodySizeFilter(new RequestBodySizeProperties()))
                .build();
    }

    @Test
    void acceptsAndDelegatesTheAuthenticatedActorAndExactAcknowledgedRequest() throws Exception {
        actor();
        when(service.initiate(eq(ORGANIZATION), eq(REQUEST), eq(ACTOR), any())).thenReturn(response("purging"));

        mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.requestId").value(Long.toString(REQUEST)))
                .andExpect(jsonPath("$.operationId").value(OPERATION))
                .andExpect(jsonPath("$.scope").value(AiAgentPurgeAcceptance.SCOPE))
                .andExpect(jsonPath("$.state").value("purging"))
                .andExpect(jsonPath("$.workspaceCount").value(2));

        ArgumentCaptor<AiAgentHoldingsPurgeRequest> request = ArgumentCaptor.forClass(AiAgentHoldingsPurgeRequest.class);
        verify(service).initiate(eq(ORGANIZATION), eq(REQUEST), eq(ACTOR), request.capture());
        assertEquals(new AiAgentHoldingsPurgeRequest(KEY, SNAPSHOT,
                AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY), request.getValue());
    }

    @Test
    void completedReplayReturnsOkAndProgressDelegatesWithoutInitiating() throws Exception {
        actor();
        when(service.initiate(eq(ORGANIZATION), eq(REQUEST), eq(ACTOR), any())).thenReturn(response("complete"));
        when(service.get(ORGANIZATION, REQUEST, ACTOR, OPERATION)).thenReturn(response("complete"));

        mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("complete"));
        mvc.perform(get(PROGRESS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.completedWorkspaceCount").value(2))
                .andExpect(jsonPath("$.completedAt").value("2026-10-15T00:01:00Z"));
        verify(service).get(ORGANIZATION, REQUEST, ACTOR, OPERATION);
    }

    @Test
    void rejectsUnknownFieldsMalformedSnapshotsAndMissingAcknowledgementsBeforeDelegating() throws Exception {
        for (String body : new String[] {
                "{}",
                BODY.replace("\"idempotencyKey\":", "\"actorId\":99,\"idempotencyKey\":"),
                BODY.replace(KEY, "not-a-uuid"),
                BODY.replace(KEY, KEY.toUpperCase(java.util.Locale.ROOT)),
                BODY.replace(SNAPSHOT, "dsr1." + "A".repeat(42)),
                BODY.replace(SNAPSHOT, "dsr1." + "A".repeat(44)),
                BODY.replace(SNAPSHOT, "dsr2." + "A".repeat(43)),
                BODY.replace(SNAPSHOT, " "),
                BODY.replace("organization_mission_holdings_v1", "single_subject"),
                BODY.replace("contents_at_each_workspace_first_invalidation_v1", "accepted_at")}) {
            mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, auth);
    }

    @Test
    void enforcesFourKiBWithoutRejectingAnExactlyBoundedValidBody() throws Exception {
        mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(4097)))
                .andExpect(status().isContentTooLarge());
        verifyNoInteractions(service, auth);
        actor();
        when(service.initiate(eq(ORGANIZATION), eq(REQUEST), eq(ACTOR), any())).thenReturn(response("purging"));
        mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON)
                .content(BODY + " ".repeat(4096 - BODY.length())))
                .andExpect(status().isAccepted());
    }

    @Test
    void mapsCurrentAuthorityMissingOperationAndSnapshotConflicts() throws Exception {
        actor();
        when(service.get(ORGANIZATION, REQUEST, ACTOR, OPERATION))
                .thenThrow(new ForbiddenException("Administrator access required"))
                .thenThrow(new ResourceNotFoundException("Mission holdings purge not found"));
        mvc.perform(get(PROGRESS)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ForbiddenException.CODE));
        mvc.perform(get(PROGRESS)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResourceNotFoundException.CODE));
        when(service.initiate(eq(ORGANIZATION), eq(REQUEST), eq(ACTOR), any()))
                .thenThrow(new ConflictException("dsr_snapshot_conflict"));
        mvc.perform(post(INITIATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    private void actor() {
        User actor = new User();
        actor.setId(ACTOR);
        when(auth.getCurrentUser()).thenReturn(actor);
    }

    private static AiAgentHoldingsPurgeDto response(String state) {
        boolean complete = "complete".equals(state);
        return new AiAgentHoldingsPurgeDto(1, OPERATION, Long.toString(REQUEST), AiAgentPurgeAcceptance.SCOPE,
                AiAgentPurgeAcceptance.CUTOFF_POLICY, "2026-10-15T00:00:00Z", state, 2, complete ? 2 : 0,
                complete ? "2026-10-15T00:01:00Z" : null, null);
    }
}

package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.mission.AiAgentRunService;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.mission.AiAgentBudgetService;
import ooo.klae.connex.backend.ai.mission.AiAgentCapabilityGate;
import ooo.klae.connex.backend.ai.mission.AiAgentRunPersistenceService;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.dto.AiAgentRunDto;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Servlet boundary validation and stable mission response contracts; authentication is tested in the real chain. */
@ExtendWith(MockitoExtension.class)
class AiAgentRunControllerTest {
    private static final String CREATE = "/api/ai/assistant/sessions/42/missions";
    private static final String BODY = """
            {"clientRequestKey":"2ec8b877-06f4-44a1-b315-e8ad20a61a8c","missionType":"record_review_v1",
            "goal":"Review this record","scope":{"kind":"record","recordKind":"person","recordId":123}}
            """;
    @Mock private AiAgentRunService service;
    @Mock private ErrorReporter errors;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AiAgentRunController(service, mock(ooo.klae.connex.backend.ai.mission.AiAgentProposalReadService.class),
                mock(ooo.klae.connex.backend.ai.mission.AiAgentProposalDecisionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(errors, new TenantContext()))
                .addFilter(new ApiRequestBodySizeFilter(new RequestBodySizeProperties()))
                .build();
    }

    @Test
    void createsAcceptedWithCanonicalLocation() throws Exception {
        AiAgentRunDto response = mock(AiAgentRunDto.class);
        when(response.id()).thenReturn(17);
        when(service.create(anyInt(), any())).thenReturn(response);
        mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/ai/assistant/missions/17"));
    }

    @Test
    void rejectsUnknownFieldsAndOutOfBoundsRequests() throws Exception {
        for (String body : new String[] {
                BODY.replace("\"goal\":", "\"ownerUserId\":4,\"goal\":"),
                BODY.replace("record_review_v1", "unsupported_mission_v1"),
                BODY.replace("\"recordId\":123", "\"recordId\":0"),
                BODY.replace("Review this record", " "),
                BODY.replace("\"goal\":", "\"limits\":{\"maxSteps\":9},\"goal\":")}) {
            mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(longs = {8192, 81919})
    void rejectsTokenLimitsBelowTheExecutableMinimumBeforeServiceAdmission(long maxTokens) throws Exception {
        mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"goal\":", "\"limits\":{\"maxTokens\":" + maxTokens + "},\"goal\":")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsTheNewTokenMinimumAtTheServletValidationBoundary() throws Exception {
        AiAgentRunDto response = mock(AiAgentRunDto.class);
        when(response.id()).thenReturn(17);
        when(service.create(anyInt(), any())).thenReturn(response);
        mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"goal\":", "\"limits\":{\"maxTokens\":81920},\"goal\":")))
                .andExpect(status().isAccepted());
        verify(service).create(42, new AiAgentRunCreateRequest("2ec8b877-06f4-44a1-b315-e8ad20a61a8c",
                "record_review_v1", "Review this record", new AiAgentRunCreateRequest.Scope("record", "person", 123),
                new AiAgentRunCreateRequest.Limits(null, AiAgentBudgetService.MIN_RUN_TOKENS, null)));
    }

    @Test
    void infeasibleGoalReturnsStable400FromRealCreationServiceBeforePersistenceOrProviderAdmission() throws Exception {
        var workspaces = mock(WorkspaceService.class);
        var runs = mock(AiAgentRunMapper.class);
        var persistence = mock(AiAgentRunPersistenceService.class);
        var gate = mock(AiAgentCapabilityGate.class);
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(workspaces.isMember(7, 11)).thenReturn(true);
        var realService = new AiAgentRunService(
                new ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard(
                        mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class)),
                mock(ooo.klae.connex.backend.ai.assistant.AiAssistantSessionReadAudit.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                runs, null, persistence, gate, null, null, null,
                new AiRestrictionEpoch(), workspaces, new ObjectMapper(), Clock.systemUTC(), ooo.klae.connex.backend.support.TenantWorkScopes.passThrough());
        var boundary = MockMvcBuilders.standaloneSetup(new AiAgentRunController(realService, mock(ooo.klae.connex.backend.ai.mission.AiAgentProposalReadService.class),
                mock(ooo.klae.connex.backend.ai.mission.AiAgentProposalDecisionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(errors, new TenantContext())).build();
        boundary.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("Review this record", "日".repeat(16000))
                        .replace("\"goal\":", "\"limits\":{\"maxTokens\":81920},\"goal\":")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("mission_budget_infeasible"));
        verifyNoInteractions(persistence, gate);
    }

    @Test
    void createAndControlEnforceTheirIndependentBodyCeilings() throws Exception {
        mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(96 * 1024 + 1)))
                .andExpect(status().isContentTooLarge());
        mvc.perform(post("/api/ai/assistant/missions/17/pause").contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(4097))).andExpect(status().isContentTooLarge());
        verifyNoInteractions(service);
    }

    @Test
    void withholdsOutputAndReportsUnavailableWithStableCodes() throws Exception {
        when(service.output(17)).thenThrow(new AiAgentGoneException("mission_output_withheld"));
        mvc.perform(get("/api/ai/assistant/missions/17/output")).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("mission_output_withheld"));
        when(service.create(anyInt(), any())).thenThrow(new AiAgentUnavailableException());
        mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("mission_unavailable"));
    }
}

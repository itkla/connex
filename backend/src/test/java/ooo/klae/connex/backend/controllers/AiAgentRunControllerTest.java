package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.mission.AiAgentRunService;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.dto.AiAgentRunDto;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.tenant.TenantContext;

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
        mvc = MockMvcBuilders.standaloneSetup(new AiAgentRunController(service))
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
                BODY.replace("record_review_v1", "record_preparation_v1"),
                BODY.replace("\"recordId\":123", "\"recordId\":0"),
                BODY.replace("Review this record", " "),
                BODY.replace("\"goal\":", "\"limits\":{\"maxSteps\":9},\"goal\":")}) {
            mvc.perform(post(CREATE).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
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

package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.ClientErrorRequest;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.ClientErrorService;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class ClientErrorControllerTest {
    @Mock private ClientErrorService clientErrorService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientErrorController(clientErrorService))
                .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
                .build();
    }

    @Test
    void validReportReturnsAcceptedWithEmptyBody() throws Exception {
        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "digest":"digest-7",
                              "message":"Render failed",
                              "stack":"at Component",
                              "path":"/dashboard"
                            }
                            """))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(clientErrorService).report(new ClientErrorRequest(
                "digest-7", "Render failed", "at Component", "/dashboard"));
    }

    @Test
    void invalidReportReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(clientErrorService);
    }

    @Test
    void oversizedFieldsReturnBadRequestWithoutCallingService() throws Exception {
        String body = "{\"message\":\"" + "x".repeat(1_001) + "\"}";

        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(clientErrorService, org.mockito.Mockito.never()).report(any());
    }
}

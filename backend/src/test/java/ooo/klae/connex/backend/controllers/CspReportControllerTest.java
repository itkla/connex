package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.services.CspReportService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

class CspReportControllerTest {
    private static final MediaType CSP_REPORT = MediaType.parseMediaType("application/csp-report");
    private static final MediaType REPORTS_JSON = MediaType.parseMediaType("application/reports+json");
    private static final ResolvedClientIp CLIENT = new ResolvedClientIp("198.51.100.7", true);

    private final CspReportService cspReportService = mock(CspReportService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolveWithProvenance(any())).thenReturn(CLIENT);
        when(cspReportService.ingest(any(), any())).thenReturn(List.of());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CspReportController(cspReportService, clientIpResolver))
                .build();
    }

    @Test
    void acceptsALegacyReportWithAnEmptyNoContentBody() throws Exception {
        String body = "{\"csp-report\":{\"effective-directive\":\"img-src\",\"blocked-uri\":\"inline\"}}";

        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content(body))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(cspReportService).ingest(eq(body), eq(CLIENT));
    }

    @Test
    void acceptsAReportingApiArray() throws Exception {
        String body = "[{\"type\":\"csp-violation\",\"body\":{\"effectiveDirective\":\"font-src\"}}]";

        mockMvc.perform(post("/api/csp-reports").contentType(REPORTS_JSON).content(body))
                .andExpect(status().isNoContent());

        verify(cspReportService).ingest(eq(body), eq(CLIENT));
    }

    @Test
    void acceptsGarbageAndEmptyBodiesWithoutFailing() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content("not json"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/csp-reports").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isNoContent());

        verify(cspReportService).ingest(eq("not json"), eq(CLIENT));
    }

    @Test
    void refusesOtherContentTypesAndOtherMethods() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(get("/api/csp-reports"))
                .andExpect(status().isMethodNotAllowed());

        verify(cspReportService, never()).ingest(any(), any());
    }
}

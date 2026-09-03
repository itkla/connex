package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceControllerTest {
    private static final String TOKEN = "w42-" + "a".repeat(64);
    private static final String SOURCE = "198.51.100.20";

    @Mock private DocumentAcceptanceService acceptanceService;
    @Mock private ClientIpResolver clientIpResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
            new DocumentAcceptanceController(acceptanceService, clientIpResolver)).build();
    }

    @Test
    void previewSerializesExpiryAsUtcIso8601AtTheHttpBoundary() throws Exception {
        Instant expiry = Instant.parse("2026-09-08T10:30:00Z");
        when(clientIpResolver.resolve(any(HttpServletRequest.class))).thenReturn(SOURCE);
        when(acceptanceService.preview(TOKEN, SOURCE)).thenReturn(
            new DocumentAcceptancePreviewDto(
                null,
                "Autumn renewal",
                "Hikari Systems",
                "r***@example.test",
                "sent",
                "pending",
                true,
                "quote",
                "Frozen document title",
                3,
                "en",
                expiry));

        mockMvc.perform(get("/api/document-acceptance/{token}", TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").value("2026-09-08T10:30:00Z"));
    }
}

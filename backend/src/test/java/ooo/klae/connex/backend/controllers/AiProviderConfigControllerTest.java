package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiProviderConfigDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class AiProviderConfigControllerTest {
    @Mock private AiProviderConfigService providerConfigService;
    @Mock private AuthService authService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User actor = new User();
        actor.setId(42);
        when(authService.getCurrentUser()).thenReturn(actor);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiProviderConfigController(providerConfigService, authService))
                .setControllerAdvice(
                        new GlobalExceptionHandler(errorReporter, new TenantContext()))
                .build();
    }

    @Test
    void currentAdminAttestationReturnsTheBindingDto() throws Exception {
        AiProviderConfigDto dto = new AiProviderConfigDto();
        dto.setProvider("azure_openai");
        dto.setZeroDataRetentionAttested(true);
        dto.setZdrAttestationVersion(1);
        dto.setZdrAttestationCurrent(true);
        dto.setPrivacyMode(AiPrivacyMode.UNMASKED);
        when(providerConfigService.attestZeroDataRetention(7, 42)).thenReturn(dto);

        mockMvc.perform(post("/api/ai/provider/zdr-attestation").param("workspaceId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privacyMode").value("UNMASKED"))
                .andExpect(jsonPath("$.zeroDataRetentionAttested").value(true))
                .andExpect(jsonPath("$.zdrAttestationVersion").value(1));

        verify(providerConfigService).attestZeroDataRetention(7, 42);
    }

    @Test
    void staleStepUpReturnsStableRecentAuthenticationCode() throws Exception {
        doThrow(new RecentAuthenticationRequiredException())
                .when(providerConfigService).attestZeroDataRetention(7, 42);

        mockMvc.perform(post("/api/ai/provider/zdr-attestation").param("workspaceId", "7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
    }

    @Test
    void foreignOrganizationIsHiddenAndNonAdminIsForbidden() throws Exception {
        doThrow(new ResourceNotFoundException("not found"))
                .when(providerConfigService).attestZeroDataRetention(8, 42);
        doThrow(new ForbiddenException("forbidden"))
                .when(providerConfigService).attestZeroDataRetention(9, 42);

        mockMvc.perform(post("/api/ai/provider/zdr-attestation").param("workspaceId", "8"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/provider/zdr-attestation").param("workspaceId", "9"))
                .andExpect(status().isForbidden());
    }
}

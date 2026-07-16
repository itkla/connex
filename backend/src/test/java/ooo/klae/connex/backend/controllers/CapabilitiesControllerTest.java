package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;

@ExtendWith(MockitoExtension.class)
class CapabilitiesControllerTest {

    @Mock private CapabilityRegistry capabilityRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CapabilitiesController(capabilityRegistry)).build();
    }

    @Test
    void capabilitiesReturnsComposedAvailability() throws Exception {
        when(capabilityRegistry.isAvailable(Capability.SSO)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_GOOGLE)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_MICROSOFT)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_SCANNING)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_IMPORT)).thenReturn(true);

        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sso").value(true))
                .andExpect(jsonPath("$.socialLogin.google").value(false))
                .andExpect(jsonPath("$.socialLogin.microsoft").value(true))
                .andExpect(jsonPath("$.mailManaged").value(false))
                .andExpect(jsonPath("$.businessCardScanning").value(true))
                .andExpect(jsonPath("$.businessCardImport").value(true));
    }
}

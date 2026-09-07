package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountMode;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.services.WorkflowTriggeredSendGate;

@ExtendWith(MockitoExtension.class)
class CapabilitiesControllerTest {

    @Mock private CapabilityRegistry capabilityRegistry;
    @Mock private PrivilegedMfaProperties privilegedMfaProperties;
    @Mock private ConnectedAccountProviders connectedAccountProviders;
    @Mock private WorkflowTriggeredSendGate workflowTriggeredSendGate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lenient().when(connectedAccountProviders.mode(ConnectedAccountProviders.GOOGLE))
                .thenReturn(ConnectedAccountMode.CUSTOM);
        lenient().when(connectedAccountProviders.mode(ConnectedAccountProviders.MICROSOFT))
                .thenReturn(ConnectedAccountMode.CUSTOM);
        mockMvc = MockMvcBuilders.standaloneSetup(new CapabilitiesController(
                capabilityRegistry,
                privilegedMfaProperties,
                connectedAccountProviders,
                workflowTriggeredSendGate)).build();
    }

    @AfterEach
    void resetRequestAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void capabilitiesReturnsComposedAvailability() throws Exception {
        when(capabilityRegistry.isAvailable(Capability.SSO)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_GOOGLE)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.SOCIAL_LOGIN_MICROSOFT)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.CONNECTED_ACCOUNTS_GOOGLE)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.CONNECTED_ACCOUNTS_MICROSOFT)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.CONNECTED_CAPTURE_GOOGLE)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.CONNECTED_CAPTURE_MICROSOFT)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.MANAGED_MAIL)).thenReturn(false);
        when(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_SCANNING)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.BUSINESS_CARD_IMPORT)).thenReturn(true);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        when(connectedAccountProviders.mode(ConnectedAccountProviders.GOOGLE))
                .thenReturn(ConnectedAccountMode.MANAGED);
        when(capabilityRegistry.isAvailable(Capability.DOCUMENT_SIGNATURE)).thenReturn(true);
        when(workflowTriggeredSendGate.enabled()).thenReturn(true);
        when(privilegedMfaProperties.isEnforced()).thenReturn(true);

        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sso").value(true))
                .andExpect(jsonPath("$.socialLogin.google").value(false))
                .andExpect(jsonPath("$.socialLogin.microsoft").value(true))
                .andExpect(jsonPath("$.connectedAccounts.google").value(true))
                .andExpect(jsonPath("$.connectedAccounts.microsoft").value(false))
                .andExpect(jsonPath("$.connectedAccountModes.google").value("managed"))
                .andExpect(jsonPath("$.connectedAccountModes.microsoft").value("custom"))
                .andExpect(jsonPath("$.connectedCapture.google").value(true))
                .andExpect(jsonPath("$.connectedCapture.microsoft").value(false))
                .andExpect(jsonPath("$.mailManaged").value(false))
                .andExpect(jsonPath("$.businessCardScanning").value(true))
                .andExpect(jsonPath("$.businessCardImport").value(true))
                .andExpect(jsonPath("$.campaignDelivery").value(true))
                .andExpect(jsonPath("$.documentSignature").value(true))
                .andExpect(jsonPath("$.workflowTriggeredSend").value(true))
                .andExpect(jsonPath("$.privilegedMfaEnforced").value(true));
    }

    @Test
    void capabilitiesReportsCampaignDeliveryWhenUnavailable() throws Exception {
        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignDelivery").value(false))
                .andExpect(jsonPath("$.workflowTriggeredSend").value(false));

        verify(capabilityRegistry).isAvailable(Capability.CAMPAIGN_DELIVERY);
        verify(capabilityRegistry).isAvailable(Capability.DOCUMENT_SIGNATURE);
        verify(workflowTriggeredSendGate).enabled();
    }
}

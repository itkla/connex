package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class AiFeatureGateTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private ObjectProvider<AiProviderReadiness> providerReadiness;
    @Mock private AiProviderReadiness readiness;

    private AiProperties properties;
    private AiFeatureGate gate;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        gate = new AiFeatureGate(properties, workspaceService, providerReadiness);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        lenient().when(workspaceService.getCurrentOrgId()).thenReturn(3);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(42);
    }

    @Test
    void disabledInstanceFlag_deniesEvenWithPermissionAndReadiness() {
        lenient().when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        lenient().when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        lenient().when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertFalse(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        assertThrows(ForbiddenException.class, () -> gate.requireAiUsable(AiFeature.DEAL_BRIEF));
    }

    @Test
    void missingProviderReadinessBean_deniesWhenEnabled() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(null);

        assertFalse(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        assertThrows(ForbiddenException.class, () -> gate.requireAiUsable(AiFeature.DEAL_BRIEF));
    }

    @Test
    void actorWithoutAiUse_deniesEvenWhenEnabledAndReady() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.COMPANY_CREATE));
        lenient().when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        lenient().when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertFalse(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        assertThrows(ForbiddenException.class, () -> gate.requireAiUsable(AiFeature.DEAL_BRIEF));
    }

    @Test
    void actorWithAiUse_allowsWhenEnabledAndReady() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertTrue(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        assertDoesNotThrow(() -> gate.requireAiUsable(AiFeature.DEAL_BRIEF));
    }

    @Test
    void generationProfileUsesOneProviderReadinessSnapshot() {
        AiGenerationProfile profile = new AiGenerationProfile(
                "bedrock", "us-east-1", "model", null, null, null, null, 2048, 0.2);
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        when(readiness.generationProfileForOrg(3, 2048, 0.2)).thenReturn(Optional.of(profile));

        assertEquals(Optional.of(profile), gate.generationProfileIfUsable(
                AiFeature.DEAL_BRIEF, 2048, 0.2));
        verify(readiness).generationProfileForOrg(3, 2048, 0.2);
        verify(readiness, never()).isReadyForOrg(3);
    }

    @Test
    void imageUseRequiresImageCapableProviderReadiness() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        when(readiness.isImageInputReadyForOrg(3)).thenReturn(false, true, true);

        assertFalse(gate.isAiUsable(AiFeature.BUSINESS_CARD_EXTRACTION));
        assertTrue(gate.isAiUsable(AiFeature.BUSINESS_CARD_EXTRACTION));
        assertDoesNotThrow(() -> gate.requireAiUsable(AiFeature.BUSINESS_CARD_EXTRACTION));
    }

    @Test
    void disabledFeatureDeniesWithoutCheckingPermissionOrReadiness() {
        properties.setEnabled(true);
        properties.getFeatures().put(AiFeature.DEAL_BRIEF, false);

        assertFalse(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        org.mockito.Mockito.verifyNoInteractions(workspaceService, providerReadiness, readiness);
    }

    @Test
    void disablingOneFeatureDoesNotDisableAnother() {
        properties.setEnabled(true);
        properties.getFeatures().put(AiFeature.DEAL_BRIEF, false);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertFalse(gate.isAiUsable(AiFeature.DEAL_BRIEF));
        assertTrue(gate.isAiUsable(AiFeature.REPORT_NARRATIVE));
    }
}

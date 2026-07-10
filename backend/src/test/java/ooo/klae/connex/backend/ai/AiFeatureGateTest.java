package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.EnumSet;

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
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(workspaceService.getCurrentUserId()).thenReturn(42);
    }

    @Test
    void disabledInstanceFlag_deniesEvenWithPermissionAndReadiness() {
        lenient().when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        lenient().when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        lenient().when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertFalse(gate.isAiUsable());
        assertThrows(ForbiddenException.class, gate::requireAiUsable);
    }

    @Test
    void missingProviderReadinessBean_deniesWhenEnabled() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(null);

        assertFalse(gate.isAiUsable());
        assertThrows(ForbiddenException.class, gate::requireAiUsable);
    }

    @Test
    void actorWithoutAiUse_deniesEvenWhenEnabledAndReady() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.COMPANY_CREATE));
        lenient().when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        lenient().when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertFalse(gate.isAiUsable());
        assertThrows(ForbiddenException.class, gate::requireAiUsable);
    }

    @Test
    void actorWithAiUse_allowsWhenEnabledAndReady() {
        properties.setEnabled(true);
        when(workspaceService.permissionsFor(7, 42)).thenReturn(EnumSet.of(Permission.AI_USE));
        when(providerReadiness.getIfAvailable()).thenReturn(readiness);
        when(readiness.isReadyForOrg(3)).thenReturn(true);

        assertTrue(gate.isAiUsable());
        assertDoesNotThrow(gate::requireAiUsable);
    }
}

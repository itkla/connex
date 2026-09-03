package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Verifies direct campaign service bodies reject an unresolved tenant before their workspace lookup. */
@ExtendWith(MockitoExtension.class)
class CampaignTransactionalTenantContextTest {

    @Mock private CampaignMapper campaignMapper;
    @Mock private SegmentService segmentService;
    @Mock private AudienceEligibilityService audienceEligibilityService;
    @Mock private WorkspaceService workspaceService;
    @Mock private TenantContext tenantContext;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private CampaignMessageMapper campaignMessageMapper;
    @Mock private CampaignSendMapper campaignSendMapper;
    @Mock private CampaignDeliveryMapper campaignDeliveryMapper;
    @Mock private PersonMapper personMapper;
    @Mock private CapabilityRegistry capabilityRegistry;
    @Mock private DeliveryProviderConfigService deliveryProviderConfigService;

    @Test
    void directTransactionalMutatorBodiesRefuseUnresolvedTenantContextBeforeWorkspaceLookup() {
        when(tenantContext.isResolved()).thenReturn(false);
        CampaignService campaignService = new CampaignService(
                campaignMapper, segmentService, audienceEligibilityService, workspaceService,
                tenantContext, authService, auditService, objectMapper);
        CampaignSendService campaignSendService = new CampaignSendService(
                campaignMapper, campaignMessageMapper, campaignSendMapper, campaignDeliveryMapper,
                personMapper, capabilityRegistry, deliveryProviderConfigService, workspaceService,
                tenantContext, authService, auditService);

        assertUnresolved(() -> campaignService.update(1, null));
        assertUnresolved(() -> campaignService.setAudience(1, null));
        assertUnresolved(() -> campaignService.snapshotAudience(1));
        assertUnresolved(() -> campaignSendService.createSend(1, null));

        verify(workspaceService, never()).getCurrentWorkspaceId();
        verify(workspaceService, never()).defaultWorkspaceIdFor(anyInt());
    }

    private static void assertUnresolved(Runnable mutation) {
        ForbiddenException exception = assertThrows(ForbiddenException.class, mutation::run);
        assertEquals("A resolved workspace membership is required", exception.getMessage());
    }
}

package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.AudienceSyncConnector;
import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignAudienceExportMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService.AudienceClassification;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Unit tests for the audience-export choke point: the fresh eligibility re-check that drops a
 * suppressed member before the push, the recorded receipt counts, the never-throws-on-push contract,
 * RBAC gating, and cross-campaign isolation. No test touches the network.
 */
@ExtendWith(MockitoExtension.class)
class AudienceExportServiceTest {

    private static final int WORKSPACE = 7;
    private static final int CAMPAIGN = 3;
    private static final int SNAPSHOT = 44;
    private static final int VERSION = 2;
    private static final String CONNECTOR = "http_list";
    private static final String LIST_ID = "list-9";

    @Mock private CampaignMapper campaignMapper;
    @Mock private CampaignAudienceExportMapper exportMapper;
    @Mock private PersonMapper personMapper;
    @Mock private AudienceEligibilityService audienceEligibilityService;
    @Mock private ConnectorConfigService connectorConfigService;
    @Mock private DeliveryProviderRouter deliveryProviderRouter;
    @Mock private CapabilityRegistry capabilityRegistry;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private AudienceSyncConnector connector;

    private AudienceExportService service() {
        return new AudienceExportService(campaignMapper, exportMapper, personMapper,
                audienceEligibilityService, connectorConfigService, deliveryProviderRouter,
                capabilityRegistry, workspaceService, authService, auditService);
    }

    private CampaignAudienceExportRequest request() {
        return new CampaignAudienceExportRequest(VERSION, CONNECTOR);
    }

    private void primeCreateExport() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN);
        campaign.setName("Q3");
        when(campaignMapper.getCampaign(WORKSPACE, CAMPAIGN)).thenReturn(campaign);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setId(SNAPSHOT);
        snapshot.setRecordType("person");
        when(campaignMapper.getSnapshot(WORKSPACE, CAMPAIGN, VERSION)).thenReturn(snapshot);
        when(connectorConfigService.isReady(WORKSPACE, CONNECTOR)).thenReturn(true);
        when(campaignMapper.getSnapshotMembers(WORKSPACE, SNAPSHOT)).thenReturn(List.of(
                member(1, "included"), member(2, "included"), member(3, "excluded")));
        when(personMapper.getByIds(eq(WORKSPACE), anyList())).thenReturn(List.of(
                person(1, "Ada Lovelace", "a@dest.test"), person(2, "Bo Ng", "b@dest.test")));
        User actor = new User();
        actor.setId(9);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(connectorConfigService.activeExternalListId(WORKSPACE, CONNECTOR)).thenReturn(LIST_ID);
        lenient().when(connectorConfigService.resolveForWorkspace(WORKSPACE, CONNECTOR))
                .thenReturn(target());
        lenient().when(deliveryProviderRouter.connectorFor(CONNECTOR)).thenReturn(connector);
    }

    @Test
    void createExport_pushesOnlyEligibleIncludedMembers_droppingASuppressedContact() {
        primeCreateExport();
        when(audienceEligibilityService.classify(eq(WORKSPACE), anyList(), eq("email"), eq("marketing")))
                .thenReturn(new AudienceClassification(Set.of(), Set.of(), Set.of(), List.of(1, 2)));
        when(audienceEligibilityService.suppressedAddresses(eq(WORKSPACE), eq("email"), anyList()))
                .thenReturn(Set.of("b@dest.test"));
        ArgumentCaptor<AudiencePush> pushCaptor = ArgumentCaptor.forClass(AudiencePush.class);
        when(connector.pushAudience(eq(target()), pushCaptor.capture()))
                .thenReturn(new AudiencePushResult(1, 0, "connector accepted"));
        when(exportMapper.getExport(eq(WORKSPACE), anyInt()))
                .thenReturn(recordedExport("completed", 1, 1, 0));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        AudiencePush push = pushCaptor.getValue();
        assertEquals(1, push.members().size());
        assertEquals("a@dest.test", push.members().get(0).email());
        assertEquals(LIST_ID, push.externalListId());

        ArgumentCaptor<CampaignAudienceExport> insertCaptor =
                ArgumentCaptor.forClass(CampaignAudienceExport.class);
        verify(exportMapper).insertExport(insertCaptor.capture());
        assertEquals(1, insertCaptor.getValue().getTotalMembers());

        ArgumentCaptor<CampaignAudienceExport> outcomeCaptor =
                ArgumentCaptor.forClass(CampaignAudienceExport.class);
        verify(exportMapper).updateOutcome(outcomeCaptor.capture());
        assertEquals("completed", outcomeCaptor.getValue().getStatus());
        assertEquals(1, outcomeCaptor.getValue().getPushedCount());
        assertEquals("completed", dto.status());
    }

    @Test
    void createExport_recordsFailedAndNeverThrowsWhenThePushFails() {
        primeCreateExport();
        when(audienceEligibilityService.classify(eq(WORKSPACE), anyList(), eq("email"), eq("marketing")))
                .thenReturn(new AudienceClassification(Set.of(), Set.of(), Set.of(), List.of(1, 2)));
        when(audienceEligibilityService.suppressedAddresses(eq(WORKSPACE), eq("email"), anyList()))
                .thenReturn(Set.of());
        when(connector.pushAudience(any(), any()))
                .thenThrow(new RuntimeException("connector down"));
        when(exportMapper.getExport(eq(WORKSPACE), anyInt()))
                .thenReturn(recordedExport("failed", 2, 0, 2));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        ArgumentCaptor<CampaignAudienceExport> outcomeCaptor =
                ArgumentCaptor.forClass(CampaignAudienceExport.class);
        verify(exportMapper).updateOutcome(outcomeCaptor.capture());
        assertEquals("failed", outcomeCaptor.getValue().getStatus());
        assertEquals(2, outcomeCaptor.getValue().getFailedCount());
        assertEquals(0, outcomeCaptor.getValue().getPushedCount());
        assertEquals("failed", dto.status());
    }

    @Test
    void createExport_rejectsADuplicateActiveExportForTheSameSnapshotAndConnector() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN);
        campaign.setName("Q3");
        when(campaignMapper.getCampaign(WORKSPACE, CAMPAIGN)).thenReturn(campaign);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setId(SNAPSHOT);
        snapshot.setRecordType("person");
        when(campaignMapper.getSnapshot(WORKSPACE, CAMPAIGN, VERSION)).thenReturn(snapshot);
        when(connectorConfigService.isReady(WORKSPACE, CONNECTOR)).thenReturn(true);
        when(exportMapper.existsActiveForSnapshotConnector(WORKSPACE, CAMPAIGN, SNAPSHOT, CONNECTOR))
                .thenReturn(true);

        assertThrows(BadRequestException.class, () -> service().createExport(CAMPAIGN, request()));
        verify(exportMapper, never()).insertExport(any());
    }

    @Test
    void getExport_isNotFoundForAnotherTenantsOrCampaignsExport() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN);
        when(campaignMapper.getCampaign(WORKSPACE, CAMPAIGN)).thenReturn(campaign);
        when(exportMapper.getExport(WORKSPACE, 99)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service().getExport(CAMPAIGN, 99));
    }

    @Test
    void readAndWriteMethods_areRbacGated() throws Exception {
        assertPermission("createExport", Permission.CAMPAIGN_MANAGE,
                int.class, CampaignAudienceExportRequest.class);
        assertPermission("listExports", Permission.CAMPAIGN_VIEW, int.class);
        assertPermission("getExport", Permission.CAMPAIGN_VIEW, int.class, int.class);
    }

    private static void assertPermission(String method, Permission expected, Class<?>... args) throws Exception {
        Method target = AudienceExportService.class.getMethod(method, args);
        RequirePermission annotation = target.getAnnotation(RequirePermission.class);
        assertTrue(annotation != null, method + " must be @RequirePermission gated");
        assertEquals(expected, annotation.value());
    }

    private static CampaignAudienceMember member(int recordId, String status) {
        CampaignAudienceMember member = new CampaignAudienceMember();
        member.setRecordId(recordId);
        member.setRecordType("person");
        member.setStatus(status);
        return member;
    }

    private static Person person(int id, String name, String email) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        person.setEmail(email);
        return person;
    }

    private static ResolvedDeliveryProvider target() {
        return ResolvedDeliveryProvider.of(CONNECTOR,
                ooo.klae.connex.backend.delivery.DeliveryChannel.EMAIL, WORKSPACE,
                ooo.klae.connex.backend.delivery.DeliveryCredentials.of(java.util.Map.of("apiKey", "k")));
    }

    private static CampaignAudienceExport recordedExport(String status, int total, int pushed, int failed) {
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(WORKSPACE);
        export.setCampaignId(CAMPAIGN);
        export.setSnapshotId(SNAPSHOT);
        export.setConnector(CONNECTOR);
        export.setExternalListId(LIST_ID);
        export.setStatus(status);
        export.setTotalMembers(total);
        export.setPushedCount(pushed);
        export.setFailedCount(failed);
        return export;
    }
}

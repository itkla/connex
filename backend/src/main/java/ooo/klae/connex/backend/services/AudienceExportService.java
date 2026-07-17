package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;
import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignAudienceExportMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService.AudienceClassification;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The campaign audience-export choke point: pushes a frozen snapshot's {@code included} members to a
 * third-party marketing connector after a fresh eligibility re-check, so a contact restricted,
 * suppressed, or unsubscribed since the snapshot was frozen is never synced to an external sender.
 * Creating an export is gated by {@code CAMPAIGN_MANAGE} and the {@code CAMPAIGN_DELIVERY} capability;
 * reaching person data additionally requires {@code CONSENT_MANAGE}, mirroring the send flow. The push
 * itself never throws to the caller — a transport or vendor failure is recorded as a failed export.
 */
@Service
@RequiredArgsConstructor
public class AudienceExportService {

    private static final Logger log = LoggerFactory.getLogger(AudienceExportService.class);
    private static final String CHANNEL = "email";
    private static final String EXPORT_PURPOSE = "marketing";

    private final CampaignMapper campaignMapper;
    private final CampaignAudienceExportMapper campaignAudienceExportMapper;
    private final PersonMapper personMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final ConnectorConfigService connectorConfigService;
    private final DeliveryProviderRouter deliveryProviderRouter;
    private final CapabilityRegistry capabilityRegistry;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /**
     * Pushes a frozen snapshot's eligible included members to a connector, recording the outcome.
     * @param campaignId the campaign
     * @param request the snapshot version and connector to push to
     * @return the recorded export with its eligible/pushed/failed tallies
     */
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceExportDto createExport(int campaignId, CampaignAudienceExportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        if (request == null) {
            throw new BadRequestException("Campaign audience export is required");
        }
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            throw new ForbiddenException("Campaign delivery is not enabled on this instance");
        }
        String connector = normalizeConnector(request.connector());
        CampaignAudienceSnapshot snapshot =
                campaignMapper.getSnapshot(workspaceId, campaignId, request.snapshotVersion());
        if (snapshot == null) {
            throw new ResourceNotFoundException("Campaign audience snapshot not found for version: "
                    + request.snapshotVersion());
        }
        if (!"person".equals(snapshot.getRecordType())) {
            throw new BadRequestException("Only person audiences can be exported");
        }
        workspaceService.requirePermission(Permission.CONSENT_MANAGE);
        if (!connectorConfigService.isReady(workspaceId, connector)) {
            throw new BadRequestException("The connector is not configured for audience sync");
        }
        if (campaignAudienceExportMapper.existsActiveForSnapshotConnector(
                workspaceId, campaignId, snapshot.getId(), connector)) {
            throw new BadRequestException("An export for this snapshot and connector already exists");
        }

        List<AudienceMember> members = eligibleMembers(workspaceId, snapshot.getId());
        String externalListId = connectorConfigService.activeExternalListId(workspaceId, connector);

        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspaceId);
        export.setCampaignId(campaignId);
        export.setSnapshotId(snapshot.getId());
        export.setConnector(connector);
        export.setExternalListId(externalListId);
        export.setStatus("running");
        export.setTotalMembers(members.size());
        export.setCreatedById(authService.getCurrentUser().getId());
        campaignAudienceExportMapper.insertExport(export);

        applyPush(workspaceId, export, connector, externalListId, members);

        auditService.record("campaign.audience_export.create", "campaign", campaignId, campaign.getName(),
                "Exported campaign audience", Map.of(
                        "exportId", export.getId(), "connector", connector, "members", members.size()));
        return CampaignAudienceExportDto.from(requireExport(workspaceId, campaignId, export.getId()));
    }

    /**
     * Lists a campaign's audience exports, newest first.
     * @param campaignId the campaign
     * @return the exports
     */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignAudienceExportDto> listExports(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return campaignAudienceExportMapper.getByCampaign(workspaceId, campaignId).stream()
                .map(CampaignAudienceExportDto::from)
                .toList();
    }

    /**
     * Returns one audience export.
     * @param campaignId the campaign
     * @param exportId the export
     * @return the export
     */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceExportDto getExport(int campaignId, int exportId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return CampaignAudienceExportDto.from(requireExport(workspaceId, campaignId, exportId));
    }

    private void applyPush(int workspaceId, CampaignAudienceExport export, String connector,
            String externalListId, List<AudienceMember> members) {
        if (members.isEmpty()) {
            export.setStatus("completed");
            campaignAudienceExportMapper.updateOutcome(export);
            return;
        }
        try {
            ResolvedDeliveryProvider target = connectorConfigService.resolveForWorkspace(workspaceId, connector);
            AudiencePushResult result = deliveryProviderRouter.connectorFor(connector)
                    .pushAudience(target, new AudiencePush(externalListId, members));
            export.setPushedCount(result.pushedCount());
            export.setFailedCount(result.failedCount());
            export.setStatus(result.pushedCount() > 0 ? "completed" : "failed");
        } catch (RuntimeException exception) {
            log.warn("Campaign audience export {} push failed in workspace {}: {}",
                    export.getId(), workspaceId, exception.getMessage());
            export.setPushedCount(0);
            export.setFailedCount(members.size());
            export.setStatus("failed");
        }
        campaignAudienceExportMapper.updateOutcome(export);
    }

    private List<AudienceMember> eligibleMembers(int workspaceId, int snapshotId) {
        List<Integer> includedIds = campaignMapper.getSnapshotMembers(workspaceId, snapshotId).stream()
                .filter(member -> "included".equals(member.getStatus()))
                .map(CampaignAudienceMember::getRecordId)
                .toList();
        if (includedIds.isEmpty()) {
            return List.of();
        }
        Map<Integer, Person> byId = new HashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, includedIds)) {
            byId.put(person.getId(), person);
        }
        List<Integer> candidateIds = new ArrayList<>();
        Map<Integer, String> addresses = new HashMap<>();
        for (int id : includedIds) {
            Person person = byId.get(id);
            if (person == null || person.getEmail() == null || person.getEmail().isBlank()) {
                continue;
            }
            candidateIds.add(id);
            addresses.put(id, person.getEmail().trim().toLowerCase(Locale.ROOT));
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        AudienceClassification classification =
                audienceEligibilityService.classify(workspaceId, candidateIds, CHANNEL, EXPORT_PURPOSE);
        List<Integer> eligibleIds = classification.includedIds();
        if (eligibleIds.isEmpty()) {
            return List.of();
        }
        Set<String> eligibleAddresses = new LinkedHashSet<>();
        for (int id : eligibleIds) {
            eligibleAddresses.add(addresses.get(id));
        }
        Set<String> suppressed =
                audienceEligibilityService.suppressedAddresses(workspaceId, CHANNEL, new ArrayList<>(eligibleAddresses));
        List<AudienceMember> members = new ArrayList<>(eligibleIds.size());
        for (int id : eligibleIds) {
            String address = addresses.get(id);
            if (suppressed.contains(address)) {
                continue;
            }
            Person person = byId.get(id);
            members.add(new AudienceMember(address, firstName(person.getName()), lastName(person.getName())));
        }
        return members;
    }

    private Campaign requireCampaign(int workspaceId, int campaignId) {
        Campaign campaign = campaignMapper.getCampaign(workspaceId, campaignId);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + campaignId);
        }
        return campaign;
    }

    private CampaignAudienceExport requireExport(int workspaceId, int campaignId, int exportId) {
        CampaignAudienceExport export = campaignAudienceExportMapper.getExport(workspaceId, exportId);
        if (export == null || export.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException("Campaign audience export not found with id: " + exportId);
        }
        return export;
    }

    private static String normalizeConnector(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new BadRequestException("A connector is required");
        }
        String connector = requested.trim().toLowerCase(Locale.ROOT);
        if (!HttpListConnector.PROVIDER_ID.equals(connector)) {
            throw new BadRequestException("Unsupported connector");
        }
        return connector;
    }

    private static String firstName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            return null;
        }
        int split = trimmed.indexOf(' ');
        return split < 0 ? trimmed : trimmed.substring(0, split);
    }

    private static String lastName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            return null;
        }
        int split = trimmed.indexOf(' ');
        return split < 0 ? null : trimToNull(trimmed.substring(split + 1));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

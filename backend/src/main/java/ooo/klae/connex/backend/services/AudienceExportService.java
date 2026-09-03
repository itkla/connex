package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.SmartValidator;

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
import ooo.klae.connex.backend.delivery.AudienceSyncConnector;
import ooo.klae.connex.backend.delivery.ChannelAddressNormalizer;
import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedAudienceTarget;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;
import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportReconciliationRequest;
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
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The campaign audience-export choke point. Transaction A freezes the prepared member ids. Connector
 * resolution then captures one configuration generation. Transaction B is the final database work:
 * it repeats locked authorization and eligibility checks, records the exact ids to be placed in the
 * provider request, refreshes the running lease, and fences the resolved configuration generation.
 * The provider call follows immediately after B commits, and transaction C records the outcome.
 * Creating an export requires {@code CAMPAIGN_MANAGE}, {@code CONSENT_MANAGE}, and the
 * {@code CAMPAIGN_DELIVERY} capability.
 *
 * <p>No database lock is held across provider egress. Consequently, a bounded final-revalidation-to-
 * provider-acceptance window remains. For locked authorization and the connector generation it starts
 * when B commits; for eligibility it starts at B's first consistent read, which loads restrictions.
 * The application portion is
 * normally milliseconds because B is immediately followed by the connector call, and provider
 * acceptance is bounded by configured connection/response timeouts (3 and 15 seconds by default). A
 * permission, consent, restriction, suppression, or connector-generation change committed inside its
 * respective window cannot retract the already-started request; it is honored on the next export,
 * while provider-side unsubscribe synchronization remains the immediate removal path. A lost response
 * or expired running lease becomes {@code needs_reconciliation} and is never silently retried.
 * Request-stable idempotency keys let a supporting provider de-duplicate a retry of the same
 * ambiguous request, while a replacement after a definite failure advances the persisted attempt.
 */
@Service
@RequiredArgsConstructor
public class AudienceExportService {

    private static final Logger log = LoggerFactory.getLogger(AudienceExportService.class);
    private static final String CHANNEL = "email";
    private static final Duration EXPORT_LEASE = Duration.ofMinutes(5);

    private final CampaignMapper campaignMapper;
    private final CampaignAudienceExportMapper campaignAudienceExportMapper;
    private final PersonMapper personMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final ConnectorConfigService connectorConfigService;
    private final DeliveryProviderRouter deliveryProviderRouter;
    private final CapabilityRegistry capabilityRegistry;
    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;
    private final SmartValidator validator;

    /**
     * Pushes a frozen snapshot's eligible included members to a connector, recording the outcome.
     * @param campaignId the campaign
     * @param request the snapshot version and connector to push to
     * @return the recorded export with its prepared, pushed, and not-pushed tallies
     */
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceExportDto createExport(int campaignId, CampaignAudienceExportRequest request) {
        if (request == null) {
            throw new BadRequestException("Campaign audience export is required");
        }
        int workspaceId = requireResolvedWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            throw new ForbiddenException("Campaign delivery is not enabled on this instance");
        }
        String connector = normalizeConnector(request.connector());
        PreparedExport prepared = inNewTransaction(() -> prepareExport(
                workspaceId, actorId, campaignId, request.snapshotVersion(), connector))
                .orElseThrow(() -> new BadRequestException(
                        "An export for this snapshot and connector already exists"));
        AudienceSyncConnector syncConnector;
        try {
            syncConnector = deliveryProviderRouter.connectorFor(connector);
        } catch (RuntimeException exception) {
            log.warn("Campaign audience export {} failed in workspace {} reason=connector_selection_failed",
                    prepared.exportId(), workspaceId);
            return inNewTransaction(() -> recordFailedBeforePush(
                    workspaceId, actorId, campaignId, prepared));
        }

        ResolvedAudienceTarget target;
        try {
            target = connectorConfigService.resolveAudienceTargetForWorkspace(workspaceId, connector);
        } catch (RuntimeException exception) {
            log.warn("Campaign audience export {} failed in workspace {} reason=provider_resolution_failed",
                    prepared.exportId(), workspaceId);
            return inNewTransaction(() -> recordFailedBeforePush(
                    workspaceId, actorId, campaignId, prepared));
        }

        FinalAudience finalAudience;
        try {
            finalAudience = inNewTransaction(() -> finalRevalidation(
                    workspaceId, actorId, campaignId, prepared, target));
        } catch (ForbiddenException exception) {
            inNewTransaction(() -> recordFailedBeforePush(
                    workspaceId, actorId, campaignId, prepared));
            throw exception;
        } catch (DeliveryProviderException exception) {
            log.warn("Campaign audience export {} failed in workspace {} reason=configuration_fence_failed",
                    prepared.exportId(), workspaceId);
            return inNewTransaction(() -> recordFailedBeforePush(
                    workspaceId, actorId, campaignId, prepared));
        }
        applyPush(finalAudience.export(), syncConnector, target, finalAudience.members(),
                finalAudience.excludedCount(), finalAudience.idempotencyKey());
        return inNewTransaction(() -> recordOutcome(
                workspaceId, actorId, campaignId, finalAudience.export()));
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
        boolean includeDetailedCounts =
                workspaceService.getCurrentPermissions().contains(Permission.CONSENT_MANAGE);
        return campaignAudienceExportMapper.getByCampaign(workspaceId, campaignId).stream()
                .map(export -> CampaignAudienceExportDto.from(export, includeDetailedCounts))
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
        boolean includeDetailedCounts =
                workspaceService.getCurrentPermissions().contains(Permission.CONSENT_MANAGE);
        return CampaignAudienceExportDto.from(
                requireExport(workspaceId, campaignId, exportId), includeDetailedCounts);
    }

    /**
     * Records an operator-confirmed provider outcome for an export that requires reconciliation.
     * Both campaign and consent management are revalidated from locked authorization rows before
     * the campaign and export rows are locked.
     * @param campaignId the campaign
     * @param exportId the reconciliation-required export
     * @param request the provider-confirmed resolution
     * @return the resolved export with its confirmed counts
     * @throws BindException when the resolution fails request validation
     */
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceExportDto reconcileExport(
            int campaignId,
            int exportId,
            CampaignAudienceExportReconciliationRequest request) throws BindException {
        if (request == null) {
            throw new BadRequestException("Campaign audience export reconciliation is required");
        }
        validate(request);
        int workspaceId = requireResolvedWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        return inNewTransaction(() -> reconcileExport(
                workspaceId, actorId, campaignId, exportId, request.resolution()));
    }

    private Optional<PreparedExport> prepareExport(
            int workspaceId, int actorId, int campaignId, int snapshotVersion, String connector) {
        requireExportPermissions(workspaceService.lockedMemberPermissionsFor(workspaceId, actorId));
        Campaign campaign = requireCampaignForUpdate(workspaceId, campaignId);
        CampaignAudienceSnapshot snapshot =
                campaignMapper.getSnapshotForShare(workspaceId, campaignId, snapshotVersion);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Campaign audience snapshot not found for version: "
                    + snapshotVersion);
        }
        if (!"person".equals(snapshot.getRecordType())) {
            throw new BadRequestException("Only person audiences can be exported");
        }
        if (!CHANNEL.equals(snapshot.getChannel())) {
            throw new BadRequestException("Only email audience snapshots can be exported");
        }
        if (!connectorConfigService.isReady(workspaceId, connector)) {
            throw new BadRequestException("The connector is not configured for audience sync");
        }
        markStaleRunningNeedsReconciliation(workspaceId, campaignId, campaign);
        if (campaignAudienceExportMapper.existsActiveForSnapshotConnector(
                workspaceId, campaignId, snapshot.getId(), connector)) {
            return Optional.empty();
        }

        List<Integer> frozenMemberIds = eligibleMemberIds(workspaceId, snapshot);
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspaceId);
        export.setCampaignId(campaignId);
        export.setSnapshotId(snapshot.getId());
        export.setConnector(connector);
        export.setFrozenMemberIdsJson(serializeMemberIds(frozenMemberIds));
        export.setPushedMemberIdsJson(serializeMemberIds(List.of()));
        export.setStatus("running");
        export.setAttempt(1);
        export.setLeaseUntil(leaseDeadline());
        export.setTotalMembers(frozenMemberIds.size());
        export.setPushedCount(0);
        export.setFailedCount(0);
        export.setCreatedById(actorId);
        campaignAudienceExportMapper.insertExport(export);
        auditService.recordStrict(
                "campaign.audience_export.create", "campaign", campaignId, campaign.getName(),
                "Created campaign audience export", Map.of(
                        "exportId", export.getId(), "connector", connector,
                        "members", frozenMemberIds.size()));
        return Optional.of(
                new PreparedExport(export.getId(), snapshotVersion, snapshot.getChannel(), snapshot.getPurpose()));
    }

    private FinalAudience finalRevalidation(
            int workspaceId,
            int actorId,
            int campaignId,
            PreparedExport prepared,
            ResolvedAudienceTarget target) {
        requireExportPermissions(workspaceService.lockedMemberPermissionsFor(workspaceId, actorId));
        requireCampaignForUpdate(workspaceId, campaignId);
        CampaignAudienceSnapshot snapshot = campaignMapper.getSnapshotForShare(
                workspaceId, campaignId, prepared.snapshotVersion());
        if (snapshot == null || snapshot.getId() <= 0
                || !prepared.channel().equals(snapshot.getChannel())
                || !prepared.purpose().equals(snapshot.getPurpose())) {
            throw new ResourceNotFoundException("Campaign audience snapshot not found for version: "
                    + prepared.snapshotVersion());
        }
        CampaignAudienceExport export = campaignAudienceExportMapper.getExportForUpdate(
                workspaceId, prepared.exportId());
        if (export == null || export.getCampaignId() != campaignId
                || export.getSnapshotId() != snapshot.getId() || !"running".equals(export.getStatus())) {
            throw new ResourceNotFoundException(
                    "Campaign audience export not found with id: " + prepared.exportId());
        }
        List<Integer> frozenMemberIds = parseMemberIds(export);
        AudienceClassification classification = audienceEligibilityService.classify(
                workspaceId, frozenMemberIds, snapshot.getChannel(), snapshot.getPurpose());
        MaterializedAudience audience = audienceMembers(workspaceId, classification.includedIds());
        int excludedCount = frozenMemberIds.size() - audience.memberIds().size();
        export.setExternalListId(target.externalListId());
        export.setPushedMemberIdsJson(serializeMemberIds(audience.memberIds()));
        export.setAttempt(campaignAudienceExportMapper.nextAttemptForSnapshotTarget(
                workspaceId, campaignId, snapshot.getId(), export.getConnector(), target.externalListId()));
        export.setFailedCount(excludedCount);
        export.setLeaseUntil(leaseDeadline());
        if (campaignAudienceExportMapper.stagePush(export) == 0) {
            throw new ResourceNotFoundException(
                    "Campaign audience export not found with id: " + prepared.exportId());
        }
        if (!connectorConfigService.isCurrentAudienceTarget(workspaceId, export.getConnector(), target)) {
            throw new DeliveryProviderException("Connector configuration changed before audience push");
        }
        return new FinalAudience(
                export, audience.members(), excludedCount,
                idempotencyKey(
                        export, prepared.snapshotVersion(), target,
                        audience.memberIds(), audience.members()));
    }

    private void applyPush(
            CampaignAudienceExport export,
            AudienceSyncConnector connector,
            ResolvedAudienceTarget target,
            List<AudienceMember> members,
            int excludedCount,
            String idempotencyKey) {
        if (members.isEmpty()) {
            export.setPushedCount(0);
            export.setStatus("completed");
            return;
        }
        try {
            AudiencePushResult result = connector.pushAudience(
                    target.provider(), new AudiencePush(target.externalListId(), members, idempotencyKey));
            if (result.outcome() == AudiencePushResult.Outcome.AMBIGUOUS
                    || !isConsistentResult(result, members.size())) {
                export.setPushedCount(0);
                export.setFailedCount(excludedCount);
                export.setStatus("needs_reconciliation");
                return;
            }
            if (result.outcome() == AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT) {
                export.setPushedCount(0);
                export.setFailedCount(excludedCount + members.size());
                export.setStatus("failed");
                return;
            }
            export.setPushedCount(result.pushedCount());
            export.setFailedCount(excludedCount + result.failedCount());
            export.setStatus(result.pushedCount() > 0 ? "completed" : "failed");
        } catch (RuntimeException exception) {
            log.warn("Campaign audience export {} failed in workspace {} reason=connector_exception",
                    export.getId(), export.getWorkspaceId());
            export.setPushedCount(0);
            export.setFailedCount(excludedCount);
            export.setStatus("needs_reconciliation");
        }
    }

    private List<Integer> eligibleMemberIds(int workspaceId, CampaignAudienceSnapshot snapshot) {
        List<Integer> includedIds = campaignMapper.getSnapshotMembers(workspaceId, snapshot.getId()).stream()
                .filter(member -> "included".equals(member.getStatus()))
                .map(CampaignAudienceMember::getRecordId)
                .distinct()
                .sorted()
                .toList();
        if (includedIds.isEmpty()) {
            return List.of();
        }
        AudienceClassification classification =
                audienceEligibilityService.classify(
                        workspaceId, includedIds, snapshot.getChannel(), snapshot.getPurpose());
        return classification.includedIds();
    }

    private MaterializedAudience audienceMembers(int workspaceId, List<Integer> eligibleIds) {
        if (eligibleIds.isEmpty()) {
            return new MaterializedAudience(List.of(), List.of());
        }
        Map<Integer, Person> byId = new HashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, eligibleIds)) {
            byId.put(person.getId(), person);
        }
        List<Integer> memberIds = new ArrayList<>(eligibleIds.size());
        List<AudienceMember> members = new ArrayList<>(eligibleIds.size());
        for (int id : eligibleIds) {
            Person person = byId.get(id);
            if (person == null) {
                continue;
            }
            String address = ChannelAddressNormalizer.addressFor(DeliveryChannel.EMAIL, person);
            if (address == null) {
                continue;
            }
            memberIds.add(id);
            members.add(new AudienceMember(address, firstName(person.getName()), lastName(person.getName())));
        }
        return new MaterializedAudience(memberIds, members);
    }

    private CampaignAudienceExportDto recordOutcome(
            int workspaceId, int actorId, int campaignId, CampaignAudienceExport export) {
        boolean includeDetailedCounts = workspaceService
                .lockedMemberPermissionsFor(workspaceId, actorId)
                .contains(Permission.CONSENT_MANAGE);
        campaignAudienceExportMapper.updateOutcome(export);
        return CampaignAudienceExportDto.from(
                requireExport(workspaceId, campaignId, export.getId()), includeDetailedCounts);
    }

    private CampaignAudienceExportDto recordFailedBeforePush(
            int workspaceId, int actorId, int campaignId, PreparedExport prepared) {
        boolean includeDetailedCounts = workspaceService
                .lockedMemberPermissionsFor(workspaceId, actorId)
                .contains(Permission.CONSENT_MANAGE);
        CampaignAudienceExport export = campaignAudienceExportMapper.getExportForUpdate(
                workspaceId, prepared.exportId());
        if (export == null || export.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException(
                    "Campaign audience export not found with id: " + prepared.exportId());
        }
        if (!"running".equals(export.getStatus())) {
            return CampaignAudienceExportDto.from(export, includeDetailedCounts);
        }
        export.setStatus("failed");
        export.setPushedCount(0);
        export.setFailedCount(export.getTotalMembers());
        campaignAudienceExportMapper.updateOutcome(export);
        return CampaignAudienceExportDto.from(
                requireExport(workspaceId, campaignId, export.getId()), includeDetailedCounts);
    }

    private String serializeMemberIds(List<Integer> memberIds) {
        try {
            return objectMapper.writeValueAsString(memberIds);
        } catch (Exception exception) {
            throw new IllegalStateException("Campaign audience export member set could not be stored", exception);
        }
    }

    private static LocalDateTime leaseDeadline() {
        return LocalDateTime.now(ZoneOffset.UTC).plus(EXPORT_LEASE);
    }

    static String idempotencyKey(
            CampaignAudienceExport export,
            int snapshotVersion,
            ResolvedAudienceTarget target,
            List<Integer> memberIds,
            List<AudienceMember> members) {
        if (memberIds.size() != members.size()) {
            throw new IllegalArgumentException("Audience export member ids and payload must align");
        }
        String targetIdentity = export.getWorkspaceId() + "\n" + export.getConnector()
                + "\n" + target.externalListId() + "\n" + target.configId()
                + "\n" + target.configVersion() + "\n" + target.provider().providerId()
                + "\n" + target.provider().channel().token();
        return "campaign-audience-" + export.getSnapshotId()
                + "-v" + snapshotVersion
                + "-t" + sha256(targetIdentity)
                + "-m" + memberPayloadHash(memberIds, members)
                + "-a" + export.getAttempt();
    }

    private CampaignAudienceExportDto reconcileExport(
            int workspaceId,
            int actorId,
            int campaignId,
            int exportId,
            String resolution) {
        requireExportPermissions(workspaceService.lockedMemberPermissionsFor(workspaceId, actorId));
        Campaign campaign = requireCampaignForUpdate(workspaceId, campaignId);
        markStaleRunningNeedsReconciliation(workspaceId, campaignId, campaign);
        CampaignAudienceExport export = campaignAudienceExportMapper.getExportForUpdate(
                workspaceId, exportId);
        if (export == null || export.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException(
                    "Campaign audience export not found with id: " + exportId);
        }
        if (matchesTerminalResolution(export.getStatus(), resolution)) {
            return CampaignAudienceExportDto.from(export, true);
        }
        if ("completed".equals(export.getStatus()) || "failed".equals(export.getStatus())) {
            throw new BadRequestException("This export was already reconciled with a different resolution");
        }
        if (!"needs_reconciliation".equals(export.getStatus())) {
            throw new BadRequestException("Only an export that needs reconciliation can be resolved");
        }
        if ("delivered".equals(resolution)) {
            applyConfirmedDelivery(export);
        } else if ("not_delivered".equals(resolution)) {
            export.setStatus("failed");
            export.setPushedCount(0);
            export.setFailedCount(export.getTotalMembers());
        } else {
            throw new BadRequestException("Unsupported campaign audience export resolution");
        }
        if (campaignAudienceExportMapper.resolveReconciliation(export) == 0) {
            throw new ResourceNotFoundException(
                    "Campaign audience export not found with id: " + exportId);
        }
        auditService.recordStrict(
                "campaign.audience_export.reconcile", "campaign", campaignId, campaign.getName(),
                "Reconciled campaign audience export", reconciliationAuditChanges(exportId, resolution, export));
        return CampaignAudienceExportDto.from(
                requireExport(workspaceId, campaignId, exportId), true);
    }

    private void applyConfirmedDelivery(CampaignAudienceExport export) {
        if (export.getExternalListId() == null || export.getExternalListId().isBlank()) {
            throw new BadRequestException("This export has no recorded provider request to confirm");
        }
        if (export.getPushedMemberIdsJson() != null) {
            int pushedCount = parseStoredMemberIds(
                    export.getPushedMemberIdsJson(), "pushed").size();
            if (pushedCount > export.getTotalMembers()) {
                throw new IllegalStateException("Stored campaign audience export pushed count is invalid");
            }
            export.setPushedCount(pushedCount);
            export.setFailedCount(export.getTotalMembers() - pushedCount);
        } else if (export.getPushedCount() != null || export.getFailedCount() != null) {
            if (export.getPushedCount() == null || export.getFailedCount() == null
                    || export.getPushedCount() + export.getFailedCount() != export.getTotalMembers()) {
                throw new BadRequestException("This historical export has no complete recorded counts to confirm");
            }
        }
        export.setStatus("completed");
    }

    private void markStaleRunningNeedsReconciliation(
            int workspaceId, int campaignId, Campaign campaign) {
        List<Integer> projectedStaleIds = campaignAudienceExportMapper
                .getByCampaign(workspaceId, campaignId).stream()
                .filter(export -> "needs_reconciliation".equals(export.getStatus()))
                .filter(export -> export.getLeaseUntil() != null)
                .map(CampaignAudienceExport::getId)
                .sorted()
                .toList();
        List<CampaignAudienceExport> staleExports = new ArrayList<>(projectedStaleIds.size());
        for (int exportId : projectedStaleIds) {
            CampaignAudienceExport export = campaignAudienceExportMapper.getExportForUpdate(
                    workspaceId, exportId);
            if (export != null && export.getCampaignId() == campaignId
                    && "running".equals(export.getStatus())) {
                staleExports.add(export);
            }
        }
        if (staleExports.isEmpty()) {
            return;
        }
        List<Integer> staleExportIds = staleExports.stream()
                .map(CampaignAudienceExport::getId)
                .toList();
        int transitioned = campaignAudienceExportMapper.markStaleRunningNeedsReconciliation(
                workspaceId, campaignId, staleExportIds);
        if (transitioned != staleExports.size()) {
            throw new IllegalStateException("Stale campaign audience exports could not be reconciled atomically");
        }
        for (CampaignAudienceExport export : staleExports) {
            auditService.recordStrict(
                    "campaign.audience_export.reconciliation_required",
                    "campaign",
                    campaignId,
                    campaign.getName(),
                    "Campaign audience export requires reconciliation",
                    Map.of(
                            "exportId", export.getId(),
                            "previousStatus", export.getStatus(),
                            "reason", "stale_lease"));
        }
    }

    private void validate(Object request) throws BindException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        validator.validate(request, bindingResult);
        if (bindingResult.hasErrors()) {
            throw new BindException(bindingResult);
        }
    }

    private List<Integer> parseMemberIds(CampaignAudienceExport export) {
        List<Integer> storedIds = parseStoredMemberIds(export.getFrozenMemberIdsJson(), "frozen");
        if (storedIds.size() != export.getTotalMembers()) {
            throw new IllegalStateException("Stored campaign audience export member count is invalid");
        }
        return storedIds;
    }

    private List<Integer> parseStoredMemberIds(String storedJson, String setName) {
        try {
            Integer[] stored = objectMapper.readValue(storedJson, Integer[].class);
            LinkedHashSet<Integer> unique = new LinkedHashSet<>();
            for (Integer id : stored) {
                if (id == null || id <= 0 || !unique.add(id)) {
                    throw new IllegalStateException(
                            "Stored campaign audience export " + setName + " member set is invalid");
                }
            }
            return List.copyOf(unique);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Stored campaign audience export " + setName + " member set is invalid", exception);
        }
    }

    private static boolean isConsistentResult(AudiencePushResult result, int memberCount) {
        if (result.outcome() == AudiencePushResult.Outcome.DEFINITE_NO_SIDE_EFFECT) {
            return result.pushedCount() == 0;
        }
        return (long) result.pushedCount() + result.failedCount() == memberCount;
    }

    private static boolean matchesTerminalResolution(String status, String resolution) {
        return ("completed".equals(status) && "delivered".equals(resolution))
                || ("failed".equals(status) && "not_delivered".equals(resolution));
    }

    private static Map<String, Object> reconciliationAuditChanges(
            int exportId, String resolution, CampaignAudienceExport export) {
        return Map.of(
                "exportId", exportId,
                "resolution", resolution,
                "countsKnown", export.getPushedCount() != null && export.getFailedCount() != null);
    }

    private static String memberPayloadHash(List<Integer> memberIds, List<AudienceMember> members) {
        MessageDigest digest = sha256Digest();
        for (int index = 0; index < members.size(); index++) {
            AudienceMember member = members.get(index);
            updateDigest(digest, Integer.toString(memberIds.get(index)));
            updateDigest(digest, member.email());
            updateDigest(digest, member.firstName());
            updateDigest(digest, member.lastName());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Campaign requireCampaignForUpdate(int workspaceId, int campaignId) {
        Campaign campaign = campaignMapper.getCampaignForUpdate(workspaceId, campaignId);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + campaignId);
        }
        return campaign;
    }

    private static void requireExportPermissions(Set<Permission> permissions) {
        if (!permissions.contains(Permission.CAMPAIGN_MANAGE)) {
            throw new ForbiddenException("Requires the CAMPAIGN_MANAGE permission in this workspace");
        }
        if (!permissions.contains(Permission.CONSENT_MANAGE)) {
            throw new ForbiddenException("Requires the CONSENT_MANAGE permission in this workspace");
        }
    }

    private int requireResolvedWorkspaceId() {
        if (!tenantContext.isResolved()) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        Integer workspaceId = tenantContext.getWorkspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        return workspaceId;
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Objects.requireNonNull(
                transaction.execute(status -> work.get()), "Campaign audience export transaction returned no result");
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

    private record PreparedExport(int exportId, int snapshotVersion, String channel, String purpose) {
    }

    private record FinalAudience(
            CampaignAudienceExport export,
            List<AudienceMember> members,
            int excludedCount,
            String idempotencyKey) {
    }

    private record MaterializedAudience(List<Integer> memberIds, List<AudienceMember> members) {

        private MaterializedAudience {
            memberIds = List.copyOf(memberIds);
            members = List.copyOf(members);
        }
    }
}

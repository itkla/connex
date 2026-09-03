package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudience;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.dto.CampaignAudienceDto;
import ooo.klae.connex.backend.dto.CampaignAudienceEstimateDto;
import ooo.klae.connex.backend.dto.CampaignAudienceMemberDto;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotSummaryDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.RecordLabelDto;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Business logic for campaigns, live audiences, estimates, and immutable audience snapshots. */
@Service
@RequiredArgsConstructor
public class CampaignService {
    private static final Set<String> STATUSES = Set.of(
            "draft", "scheduled", "active", "paused", "completed", "archived");
    private static final Set<String> RECORD_TYPES = Set.of("person", "company", "deal");
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "archived");
    private static final String DEFAULT_CHANNEL = "email";
    private static final String DEFAULT_PURPOSE = "marketing";
    private static final int MAX_DEFINITION_BYTES = 16_384;
    private static final int SAMPLE_SIZE = 25;
    private static final int SQL_BATCH_SIZE = 500;

    private final CampaignMapper campaignMapper;
    private final SegmentService segmentService;
    private final AudienceEligibilityService audienceEligibilityService;
    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Returns all campaigns in the active workspace. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignDto> list() {
        return campaignMapper.getCampaigns(workspaceService.getCurrentWorkspaceId())
                .stream().map(CampaignService::toDto).toList();
    }

    /** Returns one campaign in the active workspace. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignDto get(int id) {
        return toDto(requireCampaign(workspaceService.getCurrentWorkspaceId(), id));
    }

    /** Creates a campaign in draft status unless an explicit status is supplied. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignDto create(CampaignRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspaceId);
        campaign.setCreatedById(authService.getCurrentUser().getId());
        applyRequest(campaign, request, workspaceId, null);
        campaignMapper.insertCampaign(campaign);
        auditService.record("campaign.create", "campaign", campaign.getId(), campaign.getName(),
                "Created campaign " + campaign.getName(), null);
        return toDto(requireCampaign(workspaceId, campaign.getId()));
    }

    /** Replaces mutable campaign fields while preventing transitions out of terminal states. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignDto update(int id, CampaignRequest request) {
        int workspaceId = requireResolvedWorkspaceId();
        Campaign campaign = lockCampaignForManage(workspaceId, id).campaign();
        String previousStatus = campaign.getStatus();
        applyRequest(campaign, request, workspaceId, id);
        if (TERMINAL_STATUSES.contains(previousStatus) && !previousStatus.equals(campaign.getStatus())) {
            throw new BadRequestException("A completed or archived campaign cannot change status");
        }
        if (campaignMapper.updateCampaign(campaign) == 0) {
            throw new ResourceNotFoundException("Campaign not found with id: " + id);
        }
        auditService.record("campaign.update", "campaign", id, campaign.getName(),
                "Updated campaign " + campaign.getName(),
                auditService.singleChange("status", previousStatus, campaign.getStatus()));
        return toDto(requireCampaign(workspaceId, id));
    }

    /** Deletes a campaign after detaching its child campaigns. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, id);
        campaignMapper.clearParentReferences(workspaceId, id);
        try {
            if (campaignMapper.deleteCampaign(workspaceId, id) == 0) {
                throw new ResourceNotFoundException("Campaign not found with id: " + id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("A campaign with audience snapshots cannot be deleted");
        }
        auditService.record("campaign.delete", "campaign", id, campaign.getName(),
                "Deleted campaign " + campaign.getName(), null);
    }

    /** Stores the campaign's live audience after semantic smart-segment validation. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceDto setAudience(int campaignId, CampaignAudienceRequest request) {
        int workspaceId = requireResolvedWorkspaceId();
        if (request == null) {
            throw new BadRequestException("Campaign audience is required");
        }
        String recordType = normalizeRecordType(request.recordType());
        SegmentDefinition definition = requireDefinition(request.definition());
        segmentService.validate(recordType, definition);
        CampaignAudience audience = new CampaignAudience();
        audience.setCampaignId(campaignId);
        audience.setWorkspaceId(workspaceId);
        audience.setRecordType(recordType);
        audience.setDefinitionJson(serializeDefinition(definition));
        audience.setMode("live");
        audience.setChannel(normalizeAudienceChannel(request.channel()));
        audience.setPurpose(normalizeAudiencePurpose(request.purpose()));
        Campaign campaign = lockCampaignForManage(workspaceId, campaignId).campaign();
        CampaignAudience previous = campaignMapper.getAudience(workspaceId, campaignId);
        campaignMapper.upsertAudience(audience);
        auditService.record("campaign.audience.set", "campaign", campaignId, campaign.getName(),
                "Updated campaign audience", audienceScopeChanges(previous, audience));
        return toAudienceDto(requireAudience(workspaceId, campaignId));
    }

    /** Returns the campaign's live audience definition, or {@code null} when none is set yet. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceDto getAudience(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        CampaignAudience audience = campaignMapper.getAudience(workspaceId, campaignId);
        return audience == null ? null : toAudienceDto(audience);
    }

    /** Estimates the active audience against its stored delivery channel and consent purpose. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceEstimateDto estimateAudience(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        CampaignAudience audience = requireAudience(workspaceId, campaignId);
        requireConsentAccess(audience.getRecordType());
        AudienceEvaluation evaluation = evaluate(workspaceId, audience);
        return evaluation.toEstimate();
    }

    /** Recomputes and freezes the next campaign-local audience snapshot version. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceSnapshotDto snapshotAudience(int campaignId) {
        int workspaceId = requireResolvedWorkspaceId();
        LockedCampaign locked = lockCampaignForManage(workspaceId, campaignId);
        Campaign campaign = locked.campaign();
        CampaignAudience audience = requireAudience(workspaceId, campaignId);
        requireConsentAccess(audience.getRecordType(), locked.permissions());
        int nextSnapshotVersion = campaignMapper.nextSnapshotVersion(workspaceId, campaignId);
        AudienceEvaluation evaluation = evaluate(workspaceId, audience);
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setCampaignId(campaignId);
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setVersion(nextSnapshotVersion);
        snapshot.setRecordType(audience.getRecordType());
        snapshot.setDefinitionJson(audience.getDefinitionJson());
        snapshot.setChannel(audience.getChannel());
        snapshot.setPurpose(audience.getPurpose());
        snapshot.setEstimatedIncluded(evaluation.estimatedIncluded());
        snapshot.setExcludedNoAddress(evaluation.excludedNoAddress());
        snapshot.setExcludedConsent(evaluation.excludedConsent());
        snapshot.setExcludedSuppressed(evaluation.excludedSuppressed());
        snapshot.setExcludedRestricted(evaluation.excludedRestricted());
        snapshot.setExcludedTotal(evaluation.excludedTotal());
        snapshot.setCreatedById(authService.getCurrentUser().getId());
        campaignMapper.insertSnapshot(snapshot);
        insertMembers(workspaceId, snapshot.getId(), audience.getRecordType(), evaluation.records());
        auditService.record("campaign.audience.snapshot", "campaign", campaignId, campaign.getName(),
                "Created campaign audience snapshot", Map.of(
                        "version", snapshot.getVersion(),
                        "channel", snapshot.getChannel(),
                        "purpose", snapshot.getPurpose()));
        return getSnapshotForShareInternal(workspaceId, campaignId, snapshot.getVersion());
    }

    /** Lists immutable audience snapshot summaries for a campaign. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignAudienceSnapshotSummaryDto> listSnapshots(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        List<CampaignAudienceSnapshotSummaryDto> snapshots = campaignMapper.getSnapshots(workspaceId, campaignId);
        if (snapshots.stream().anyMatch(snapshot -> "person".equals(snapshot.recordType()))) {
            workspaceService.requirePermission(Permission.CONSENT_MANAGE);
        }
        return snapshots;
    }

    /** Returns one immutable audience snapshot by campaign-local version. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceSnapshotDto getSnapshot(int campaignId, int version) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        if (version <= 0) {
            throw new BadRequestException("Snapshot version must be positive");
        }
        return getSnapshotInternal(workspaceId, campaignId, version);
    }

    private AudienceEvaluation evaluate(int workspaceId, CampaignAudience audience) {
        SegmentDefinition definition = parseDefinition(audience.getDefinitionJson());
        int userId = authService.getCurrentUser().getId();
        List<Integer> evaluatedIds = "person".equals(audience.getRecordType())
                ? segmentService.evaluateIncludingRestrictedPeople(
                        workspaceId, userId, audience.getRecordType(), definition)
                : segmentService.evaluate(workspaceId, userId, audience.getRecordType(), definition);
        List<Integer> candidateIds = evaluatedIds.stream().distinct().sorted().toList();
        if (!"person".equals(audience.getRecordType())) {
            List<ClassifiedRecord> records = candidateIds.stream()
                    .map(id -> new ClassifiedRecord(id, "included", null)).toList();
            return evaluation(
                    audience.getRecordType(), audience.getChannel(), audience.getPurpose(),
                    records, 0, 0, 0, 0);
        }

        AudienceEligibilityService.AudienceClassification classification =
                audienceEligibilityService.classify(
                        workspaceId, candidateIds, audience.getChannel(), audience.getPurpose());
        List<ClassifiedRecord> records = new ArrayList<>(candidateIds.size());
        for (int id : candidateIds) {
            String reason = classification.reasonFor(id);
            records.add(reason == null
                    ? new ClassifiedRecord(id, "included", null)
                    : new ClassifiedRecord(id, "excluded", reason));
        }
        return evaluation(audience.getRecordType(), audience.getChannel(), audience.getPurpose(), records,
                classification.noAddress().size(),
                classification.consentBlocked().size(), classification.suppressed().size(),
                classification.restricted().size());
    }

    private AudienceEvaluation evaluation(
            String recordType, String channel, String purpose, List<ClassifiedRecord> records,
            int excludedNoAddress, int excludedConsent,
            int excludedSuppressed, int excludedRestricted) {
        List<Integer> includedIds = records.stream()
                .filter(record -> "included".equals(record.status()))
                .map(ClassifiedRecord::recordId)
                .toList();
        List<Integer> sampleIds = includedIds.stream().limit(SAMPLE_SIZE).toList();
        List<RecordLabelDto> sampleLabels = segmentService.labels(recordType, sampleIds);
        return new AudienceEvaluation(
                List.copyOf(records), channel, purpose, includedIds.size(), excludedNoAddress,
                excludedConsent, excludedSuppressed, excludedRestricted,
                excludedNoAddress + excludedConsent + excludedSuppressed + excludedRestricted,
                List.copyOf(sampleLabels));
    }

    private void insertMembers(
            int workspaceId, int snapshotId, String recordType, List<ClassifiedRecord> records) {
        List<CampaignAudienceMember> members = records.stream().map(record -> {
            CampaignAudienceMember member = new CampaignAudienceMember();
            member.setSnapshotId(snapshotId);
            member.setWorkspaceId(workspaceId);
            member.setRecordType(recordType);
            member.setRecordId(record.recordId());
            member.setStatus(record.status());
            member.setExclusionReason(record.exclusionReason());
            return member;
        }).toList();
        forEachBatch(members, batch -> campaignMapper.insertSnapshotMembers(workspaceId, batch));
    }

    private static <T> void forEachBatch(List<T> values, java.util.function.Consumer<List<T>> consumer) {
        for (int offset = 0; offset < values.size(); offset += SQL_BATCH_SIZE) {
            consumer.accept(values.subList(offset, Math.min(values.size(), offset + SQL_BATCH_SIZE)));
        }
    }

    private CampaignAudienceSnapshotDto getSnapshotInternal(int workspaceId, int campaignId, int version) {
        CampaignAudienceSnapshot snapshot = campaignMapper.getSnapshot(workspaceId, campaignId, version);
        if (snapshot == null) {
            throw new ResourceNotFoundException(
                    "Campaign audience snapshot not found for version: " + version);
        }
        requireConsentAccess(snapshot.getRecordType());
        List<CampaignAudienceMemberDto> members = campaignMapper.getSnapshotMembers(workspaceId, snapshot.getId())
                .stream()
                .map(member -> new CampaignAudienceMemberDto(
                        member.getRecordType(), member.getRecordId(), member.getStatus(),
                        member.getExclusionReason()))
                .toList();
        return toSnapshotDto(snapshot, members);
    }

    private CampaignAudienceSnapshotDto getSnapshotForShareInternal(
            int workspaceId, int campaignId, int version) {
        CampaignAudienceSnapshot snapshot = campaignMapper.getSnapshotForShare(
                workspaceId, campaignId, version);
        if (snapshot == null) {
            throw new ResourceNotFoundException(
                    "Campaign audience snapshot not found for version: " + version);
        }
        List<CampaignAudienceMemberDto> members = campaignMapper.getSnapshotMembersForShare(
                        workspaceId, snapshot.getId())
                .stream()
                .map(member -> new CampaignAudienceMemberDto(
                        member.getRecordType(), member.getRecordId(), member.getStatus(),
                        member.getExclusionReason()))
                .toList();
        return toSnapshotDto(snapshot, members);
    }

    private CampaignAudienceSnapshotDto toSnapshotDto(
            CampaignAudienceSnapshot snapshot, List<CampaignAudienceMemberDto> members) {
        return new CampaignAudienceSnapshotDto(
                snapshot.getCampaignId(), snapshot.getVersion(), snapshot.getRecordType(),
                parseDefinition(snapshot.getDefinitionJson()), snapshot.getChannel(), snapshot.getPurpose(),
                snapshot.getEstimatedIncluded(), snapshot.getExcludedTotal(), snapshot.getExcludedNoAddress(),
                snapshot.getExcludedConsent(),
                snapshot.getExcludedSuppressed(), snapshot.getExcludedRestricted(),
                snapshot.getCreatedById(), snapshot.getCreatedAt(), members);
    }

    private void applyRequest(Campaign campaign, CampaignRequest request, int workspaceId, Integer currentId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Campaign name is required");
        }
        String type = normalize(request.type());
        if (type == null || !type.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new BadRequestException("Campaign type is invalid");
        }
        String status = request.status() == null
                ? campaign.getStatus() == null ? "draft" : campaign.getStatus()
                : normalize(request.status());
        if (!STATUSES.contains(status)) {
            throw new BadRequestException("Campaign status is invalid");
        }
        validateBudget(request.budgetAmount(), request.budgetCurrency());
        if (request.startAt() != null && request.endAt() != null
                && request.startAt().isAfter(request.endAt())) {
            throw new BadRequestException("Campaign start must not be after its end");
        }
        if (request.ownerUserId() != null && !workspaceService.isMember(workspaceId, request.ownerUserId())) {
            throw new BadRequestException("Campaign owner must be an active workspace member");
        }
        if (currentId != null && currentId.equals(request.parentCampaignId())) {
            throw new BadRequestException("A campaign cannot be its own parent");
        }
        if (request.parentCampaignId() != null) {
            requireCampaign(workspaceId, request.parentCampaignId());
        }
        campaign.setName(request.name().trim());
        campaign.setObjective(trimToNull(request.objective()));
        campaign.setType(type);
        campaign.setStatus(status);
        campaign.setOwnerUserId(request.ownerUserId());
        campaign.setBudgetAmount(request.budgetAmount());
        campaign.setBudgetCurrency(request.budgetCurrency() == null
                ? null : request.budgetCurrency().trim().toUpperCase(Locale.ROOT));
        campaign.setStartAt(request.startAt());
        campaign.setEndAt(request.endAt());
        campaign.setParentCampaignId(request.parentCampaignId());
    }

    private static void validateBudget(BigDecimal amount, String currency) {
        if ((amount == null) != (currency == null)) {
            throw new BadRequestException("Campaign budget amount and currency must be supplied together");
        }
        if (amount == null) {
            return;
        }
        if (amount.signum() < 0 || amount.scale() > 2
                || amount.precision() - amount.scale() > 13) {
            throw new BadRequestException("Campaign budget must be a non-negative DECIMAL(15,2) value");
        }
        if (!currency.trim().matches("[A-Za-z]{3}")) {
            throw new BadRequestException("Campaign budget currency must contain exactly three letters");
        }
        try {
            Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Campaign budget currency must be an ISO-4217 code");
        }
    }

    private Campaign requireCampaign(int workspaceId, int id) {
        Campaign campaign = campaignMapper.getCampaign(workspaceId, id);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + id);
        }
        return campaign;
    }

    private void requireConsentAccess(String recordType) {
        if ("person".equals(recordType)) {
            workspaceService.requirePermission(Permission.CONSENT_MANAGE);
        }
    }

    private LockedCampaign lockCampaignForManage(int workspaceId, int id) {
        Set<Permission> permissions = workspaceService.lockedMemberPermissionsFor(
                workspaceId, workspaceService.getCurrentUserId());
        Campaign campaign = campaignMapper.getCampaignForUpdate(workspaceId, id);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + id);
        }
        if (!permissions.contains(Permission.CAMPAIGN_MANAGE)) {
            throw new ForbiddenException("Requires the CAMPAIGN_MANAGE permission in this workspace");
        }
        return new LockedCampaign(campaign, permissions);
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

    private static void requireConsentAccess(String recordType, Set<Permission> permissions) {
        if ("person".equals(recordType) && !permissions.contains(Permission.CONSENT_MANAGE)) {
            throw new ForbiddenException("Requires the CONSENT_MANAGE permission in this workspace");
        }
    }

    private CampaignAudience requireAudience(int workspaceId, int campaignId) {
        CampaignAudience audience = campaignMapper.getAudience(workspaceId, campaignId);
        if (audience == null) {
            throw new ResourceNotFoundException("Campaign audience is not configured");
        }
        return audience;
    }

    private static Map<String, Object> audienceScopeChanges(
            CampaignAudience previous, CampaignAudience current) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("channel", change(
                previous == null ? null : previous.getChannel(), current.getChannel()));
        changes.put("purpose", change(
                previous == null ? null : previous.getPurpose(), current.getPurpose()));
        return changes;
    }

    private static Map<String, Object> change(Object before, Object after) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("old", before);
        delta.put("new", after);
        return delta;
    }

    private SegmentDefinition requireDefinition(SegmentDefinition definition) {
        if (definition == null) {
            throw new BadRequestException("Campaign audience definition is required");
        }
        return definition;
    }

    private String serializeDefinition(SegmentDefinition definition) {
        try {
            String json = objectMapper.writeValueAsString(definition);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
                throw new BadRequestException("Campaign audience definition is too large");
            }
            return json;
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Campaign audience definition is invalid");
        }
    }

    private SegmentDefinition parseDefinition(String json) {
        try {
            return objectMapper.readValue(json, SegmentDefinition.class);
        } catch (Exception exception) {
            throw new BadRequestException("Stored campaign audience definition is invalid");
        }
    }

    private CampaignAudienceDto toAudienceDto(CampaignAudience audience) {
        return new CampaignAudienceDto(
                audience.getCampaignId(), audience.getRecordType(),
                parseDefinition(audience.getDefinitionJson()), audience.getMode(), audience.getChannel(),
                audience.getPurpose(), audience.getUpdatedAt());
    }

    private static CampaignDto toDto(Campaign campaign) {
        return new CampaignDto(
                campaign.getId(), campaign.getName(), campaign.getObjective(), campaign.getType(),
                campaign.getStatus(), campaign.getOwnerUserId(), campaign.getBudgetAmount(),
                campaign.getBudgetCurrency(), campaign.getStartAt(), campaign.getEndAt(),
                campaign.getParentCampaignId(), campaign.getCreatedById(), campaign.getCreatedAt(),
                campaign.getUpdatedAt());
    }

    private static String normalizeRecordType(String value) {
        String normalized = normalize(value);
        if (normalized == null || !RECORD_TYPES.contains(normalized)) {
            throw new BadRequestException("Campaign audience record type is invalid");
        }
        return normalized;
    }

    private static String normalizeAudienceChannel(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_CHANNEL : normalize(value);
        if (!("email".equals(normalized) || "sms".equals(normalized))) {
            throw new BadRequestException("Campaign audience channel must be email or sms");
        }
        return normalized;
    }

    private static String normalizeAudiencePurpose(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_PURPOSE : normalize(value);
        if (!normalized.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new BadRequestException("Campaign audience purpose is invalid");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ClassifiedRecord(int recordId, String status, String exclusionReason) {
    }

    private record LockedCampaign(Campaign campaign, Set<Permission> permissions) {
    }

    private record AudienceEvaluation(
            List<ClassifiedRecord> records,
            String channel,
            String purpose,
            int estimatedIncluded,
            int excludedNoAddress,
            int excludedConsent,
            int excludedSuppressed,
            int excludedRestricted,
            int excludedTotal,
            List<RecordLabelDto> sampleLabels) {
        private CampaignAudienceEstimateDto toEstimate() {
            return new CampaignAudienceEstimateDto(
                    channel, purpose, estimatedIncluded, excludedNoAddress, excludedConsent,
                    excludedSuppressed, excludedRestricted, excludedTotal, sampleLabels);
        }
    }

}

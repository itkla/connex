package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicateReviewDecisionRequest;
import ooo.klae.connex.backend.dto.DuplicateReviewEvidenceDto;
import ooo.klae.connex.backend.dto.DuplicateReviewItemDto;
import ooo.klae.connex.backend.dto.DuplicateReviewItemRow;
import ooo.klae.connex.backend.dto.DuplicateReviewMaterializationKey;
import ooo.klae.connex.backend.dto.DuplicateReviewMemberDto;
import ooo.klae.connex.backend.dto.DuplicateReviewQuery;
import ooo.klae.connex.backend.dto.DuplicateReviewSummaryDto;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DuplicateReviewMapper;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Projects current strong identity collisions into bounded, evidence-specific review items.
 * Pair expansion stops at twenty members; larger groups become one non-dismissible oversized item.
 */
@Service
@RequiredArgsConstructor
public class DuplicateReviewService {

    static final int MAX_GROUP_SIZE_FOR_PAIR_EXPANSION = 20;
    private static final int REBUILD_GROUP_PAGE_SIZE = 500;

    private final DuplicateReviewMapper duplicateReviewMapper;
    private final IdentityCollisionMapper identityCollisionMapper;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /**
     * Returns a database-pageable current review queue. Lowercase query kinds use the
     * {@link IdentityKind} database vocabulary; response evidence uses {@link DuplicateMatchKind}.
     *
     * @param query validated filters and pagination
     * @return visible review items and total
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 4)
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<DuplicateReviewItemDto> list(DuplicateReviewQuery query) {
        DuplicateReviewQuery required = Objects.requireNonNull(query, "query");
        validateCompatibility(required.getRecordType(), required.getKind());
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long offset = Math.multiplyExact((long) required.getPage() - 1L, required.getSize());
        long total = duplicateReviewMapper.countVisibleItems(
            workspaceId,
            required.getRecordType(),
            required.getKind(),
            required.getState(),
            MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
        List<DuplicateReviewItemDto> items = duplicateReviewMapper.findVisibleItems(
                workspaceId,
                required.getRecordType(),
                required.getKind(),
                required.getState(),
                required.getSize(),
                offset,
                MAX_GROUP_SIZE_FOR_PAIR_EXPANSION)
            .stream()
            .map(DuplicateReviewService::toDto)
            .toList();
        return new PageResponse<>(items, total);
    }

    /**
     * Returns current open item counts by supported record type.
     *
     * @return open person and company counts
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 4)
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewSummaryDto summary() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return new DuplicateReviewSummaryDto(
            duplicateReviewMapper.countVisibleItems(
                workspaceId, "person", null, "open", MAX_GROUP_SIZE_FOR_PAIR_EXPANSION),
            duplicateReviewMapper.countVisibleItems(
                workspaceId, "company", null, "open", MAX_GROUP_SIZE_FOR_PAIR_EXPANSION));
    }

    /**
     * Dismisses one current pair after locked report-read and type-specific permission revalidation.
     *
     * @param request exact pair and evidence returned by the queue
     * @return current dismissed item
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewItemDto dismiss(DuplicateReviewDecisionRequest request) {
        return decide(Objects.requireNonNull(request, "request"), true);
    }

    /**
     * Reopens one current dismissed pair after locked report-read and type-specific permission revalidation.
     *
     * @param request exact pair and evidence returned by the queue
     * @return current open item
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.REPORT_READ)
    public DuplicateReviewItemDto reopen(DuplicateReviewDecisionRequest request) {
        return decide(Objects.requireNonNull(request, "request"), false);
    }

    /**
     * Rebuilds materialized review state for every current collision group in a workspace.
     * Existing decisions are retained and only their current-evidence marker changes.
     *
     * @param workspaceId workspace already protected by the duplicate-decision hierarchy
     * @param detectedAt rebuild timestamp
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshWorkspaceItems(int workspaceId, LocalDateTime detectedAt) {
        requireWorkspace(workspaceId);
        Objects.requireNonNull(detectedAt, "detectedAt");
        duplicateReviewMapper.deactivateWorkspace(workspaceId);
        String afterRecordType = null;
        String afterKind = null;
        String afterNormalizedValue = null;
        while (true) {
            List<IdentityCollisionGroupKey> groups =
                identityCollisionMapper.findVisibleGroupKeysAfter(
                    workspaceId,
                    afterRecordType,
                    afterKind,
                    afterNormalizedValue,
                    REBUILD_GROUP_PAGE_SIZE);
            if (!groups.isEmpty()) {
                duplicateReviewMapper.upsertEvidenceGroups(
                    workspaceId,
                    groups.stream()
                        .map(group -> materializationKey(
                            Objects.requireNonNull(group, "collision group")))
                        .toList(),
                    detectedAt,
                    MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
            }
            if (groups.size() < REBUILD_GROUP_PAGE_SIZE) {
                return;
            }
            IdentityCollisionGroupKey last = groups.getLast();
            afterRecordType = last.recordType();
            afterKind = last.kind();
            afterNormalizedValue = last.normalizedValue();
        }
    }

    /**
     * Refreshes one person evidence group while its canonical identity-group lock is held.
     *
     * @param workspaceId owning workspace
     * @param kind lowercase canonical identity kind
     * @param normalizedValue canonical identity value
     * @param detectedAt collision rebuild timestamp
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshPersonEvidence(
            int workspaceId,
            String kind,
            String normalizedValue,
            LocalDateTime detectedAt) {
        reconcileFromLiveState(
            workspaceId,
            new IdentityCollisionGroupKey("person", kind, normalizedValue),
            detectedAt);
    }

    /**
     * Refreshes one company evidence group while its canonical identity-group lock is held.
     *
     * @param workspaceId owning workspace
     * @param kind lowercase canonical identity kind
     * @param normalizedValue canonical identity value
     * @param detectedAt collision rebuild timestamp
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshCompanyEvidence(
            int workspaceId,
            String kind,
            String normalizedValue,
            LocalDateTime detectedAt) {
        reconcileFromLiveState(
            workspaceId,
            new IdentityCollisionGroupKey("company", kind, normalizedValue),
            detectedAt);
    }

    static String evidenceFingerprint(
            String recordType,
            String kind,
            String normalizedValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, requireText(recordType, "record type"));
            updateDigest(digest, requireText(kind, "identity kind"));
            updateDigest(digest, requireText(normalizedValue, "normalized identity value"));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static DuplicateReviewMaterializationKey materializationKey(
            IdentityCollisionGroupKey group) {
        String recordType = requireText(group.recordType(), "record type");
        String kind = requireText(group.kind(), "identity kind");
        String normalizedValue = requireText(
            group.normalizedValue(), "normalized identity value");
        validateCompatibility(recordType, kind);
        return new DuplicateReviewMaterializationKey(
            recordType,
            kind,
            normalizedValue,
            evidenceFingerprint(recordType, kind, normalizedValue));
    }

    private DuplicateReviewItemDto decide(
            DuplicateReviewDecisionRequest request,
            boolean dismiss) {
        String recordType = request.recordType();
        String kind = request.kind();
        if (recordType == null || kind == null) {
            throw new BadRequestException("Duplicate review requires record type and identity kind");
        }
        validateCompatibility(recordType, kind);
        int lowRecordId = Math.min(request.recordIdA(), request.recordIdB());
        int highRecordId = Math.max(request.recordIdA(), request.recordIdB());
        if (lowRecordId == highRecordId) {
            throw new BadRequestException("Duplicate review requires two distinct records");
        }
        Permission permission = mutationPermission(recordType);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequirePermissions(
            workspaceId,
            Map.of(actorId, Set.of(Permission.REPORT_READ, permission)));
        duplicateDecisionLockService.lockCurrentOrganization();
        Long itemId = duplicateReviewMapper.lockCurrentPair(
            workspaceId,
            recordType,
            kind,
            lowRecordId,
            highRecordId,
            request.evidenceFingerprint());
        if (itemId == null) {
            throw staleEvidence();
        }
        DuplicateReviewItemRow visible = duplicateReviewMapper.findVisibleItemById(
            workspaceId, itemId, MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
        if (visible == null || !"pair".equals(visible.getItemType())) {
            throw staleEvidence();
        }
        int changed = dismiss
            ? duplicateReviewMapper.dismiss(
                workspaceId, itemId, actorId, normalizeNote(request.note()), utcNow())
            : duplicateReviewMapper.reopen(workspaceId, itemId);
        if (changed != 1) {
            throw staleEvidence();
        }
        DuplicateReviewItemRow result = duplicateReviewMapper.findVisibleItemById(
            workspaceId, itemId, MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
        if (result == null) {
            throw staleEvidence();
        }
        return toDto(result);
    }

    private void reconcileFromLiveState(
            int workspaceId,
            IdentityCollisionGroupKey group,
            LocalDateTime detectedAt) {
        requireWorkspace(workspaceId);
        String recordType = requireText(group.recordType(), "record type");
        String kind = requireText(group.kind(), "identity kind");
        String normalizedValue = requireText(
            group.normalizedValue(), "normalized identity value");
        validateCompatibility(recordType, kind);
        String fingerprint = evidenceFingerprint(recordType, kind, normalizedValue);
        duplicateReviewMapper.deactivateEvidence(
            workspaceId, recordType, kind, fingerprint);
        if ("person".equals(recordType)) {
            duplicateReviewMapper.upsertPersonPairs(
                workspaceId, kind, normalizedValue, fingerprint, detectedAt,
                MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
            duplicateReviewMapper.upsertPersonOversizedGroup(
                workspaceId, kind, normalizedValue, fingerprint, detectedAt,
                MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
            return;
        }
        duplicateReviewMapper.upsertCompanyPairs(
            workspaceId, kind, normalizedValue, fingerprint, detectedAt,
            MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
        duplicateReviewMapper.upsertCompanyOversizedGroup(
            workspaceId, kind, normalizedValue, fingerprint, detectedAt,
            MAX_GROUP_SIZE_FOR_PAIR_EXPANSION);
    }

    private static DuplicateReviewItemDto toDto(DuplicateReviewItemRow row) {
        DuplicateReviewItemRow required = Objects.requireNonNull(row, "review item");
        String itemType = requireText(required.getItemType(), "review item type");
        List<DuplicateReviewMemberDto> members;
        if ("pair".equals(itemType)) {
            members = List.of(
                member(
                    required.getLowRecordId(),
                    required.getLowName(),
                    required.getLowCompanyName(),
                    required.getLowOwnerId(),
                    required.isLowOwnedByActiveWorkspace()),
                member(
                    required.getHighRecordId(),
                    required.getHighName(),
                    required.getHighCompanyName(),
                    required.getHighOwnerId(),
                    required.isHighOwnedByActiveWorkspace()));
        } else if ("oversized_group".equals(itemType)) {
            members = List.of();
        } else {
            throw new IllegalStateException("Unsupported duplicate review item type");
        }
        DuplicateMatchKind matchKind = switch (requireText(required.getKind(), "identity kind")) {
            case "email" -> DuplicateMatchKind.EMAIL;
            case "phone" -> DuplicateMatchKind.PHONE;
            case "domain" -> DuplicateMatchKind.DOMAIN;
            case "external_id" -> DuplicateMatchKind.EXTERNAL_ID;
            default -> throw new IllegalStateException("Unsupported duplicate review identity kind");
        };
        return new DuplicateReviewItemDto(
            itemType,
            requireText(required.getRecordType(), "record type"),
            DuplicateMatchStrength.STRONG,
            new DuplicateReviewEvidenceDto(matchKind),
            members,
            required.getCollisionSize(),
            "oversized_group".equals(itemType),
            Objects.requireNonNull(required.getDetectedAt(), "detection timestamp"),
            requireText(required.getState(), "review state"),
            requireText(required.getEvidenceFingerprint(), "evidence fingerprint"),
            required.getDismissedAt(),
            required.getDismissedByUserId());
    }

    private static DuplicateReviewMemberDto member(
            Integer recordId,
            String name,
            String companyName,
            Integer ownerId,
            boolean ownedByActiveWorkspace) {
        if (recordId == null || recordId <= 0) {
            throw new IllegalStateException("Duplicate review pair member is missing");
        }
        return new DuplicateReviewMemberDto(
            recordId,
            requireText(name, "record name"),
            companyName,
            ownerId,
            ownedByActiveWorkspace);
    }

    private static Permission mutationPermission(String recordType) {
        return switch (recordType) {
            case "person" -> Permission.PERSON_UPDATE;
            case "company" -> Permission.COMPANY_UPDATE;
            default -> throw new BadRequestException("Duplicate review requires person or company");
        };
    }

    private static void validateCompatibility(String recordType, String kind) {
        if (recordType == null || kind == null) {
            return;
        }
        boolean compatible = switch (recordType) {
            case "person" -> Set.of("email", "phone", "external_id").contains(kind);
            case "company" -> Set.of("domain", "phone", "external_id").contains(kind);
            default -> false;
        };
        if (!compatible) {
            throw new BadRequestException("Identity kind is incompatible with record type");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String normalized = note.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static ConflictException staleEvidence() {
        return new ConflictException(
            "Duplicate evidence changed or is no longer reviewable; refresh the review queue");
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Duplicate review requires " + label);
        }
        return value;
    }

    private static void requireWorkspace(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Duplicate review requires a workspace");
        }
    }
}

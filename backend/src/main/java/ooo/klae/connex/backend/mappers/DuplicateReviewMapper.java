package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.DuplicateReviewItemRow;
import ooo.klae.connex.backend.dto.DuplicateReviewMaterializationKey;

/** Workspace-scoped persistence for evidence-specific duplicate review items. */
public interface DuplicateReviewMapper {

    int deactivateWorkspace(@Param("workspaceId") int workspaceId);

    int deactivateEvidence(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("evidenceFingerprint") String evidenceFingerprint);

    int upsertPersonPairs(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("evidenceFingerprint") String evidenceFingerprint,
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    int upsertPersonOversizedGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("evidenceFingerprint") String evidenceFingerprint,
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    int upsertCompanyPairs(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("evidenceFingerprint") String evidenceFingerprint,
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    int upsertCompanyOversizedGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("evidenceFingerprint") String evidenceFingerprint,
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    int upsertEvidenceGroups(
        @Param("workspaceId") int workspaceId,
        @Param("groups") List<DuplicateReviewMaterializationKey> groups,
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    long countVisibleItems(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("state") String state,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    List<DuplicateReviewItemRow> findVisibleItems(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("state") String state,
        @Param("limit") int limit,
        @Param("offset") long offset,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);

    Long lockCurrentPair(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("lowRecordId") int lowRecordId,
        @Param("highRecordId") int highRecordId,
        @Param("evidenceFingerprint") String evidenceFingerprint);

    int dismiss(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("actorId") int actorId,
        @Param("note") String note,
        @Param("decidedAt") LocalDateTime decidedAt);

    int reopen(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int clearDismissedBy(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    int clearDismissedByAnywhere(@Param("userId") int userId);

    DuplicateReviewItemRow findVisibleItemById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("maximumPairGroupSize") int maximumPairGroupSize);
}

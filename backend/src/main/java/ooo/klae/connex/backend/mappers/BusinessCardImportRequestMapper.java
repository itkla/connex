package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;

/**
 * Persists tenant-scoped business-card import idempotency claims and results.
 */
public interface BusinessCardImportRequestMapper {

    int reserve(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("reservationSlot") int reservationSlot,
            @Param("submissionExpiresAt") LocalDateTime submissionExpiresAt,
            @Param("expiresAt") LocalDateTime expiresAt);

    BusinessCardImportRecord get(
            @Param("workspaceId") int workspaceId,
            @Param("idempotencyKey") String idempotencyKey);

    BusinessCardImportRecord getForUpdate(
            @Param("workspaceId") int workspaceId,
            @Param("idempotencyKey") String idempotencyKey);

    int bindReservation(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") byte[] requestFingerprint,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("now") LocalDateTime now);

    int renewReservation(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("submissionExpiresAt") LocalDateTime submissionExpiresAt,
            @Param("now") LocalDateTime now);

    int deleteAbandonedReservations(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId,
            @Param("now") LocalDateTime now);

    int complete(
            @Param("workspaceId") int workspaceId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("personId") int personId,
            @Param("attachmentId") int attachmentId,
            @Param("companyId") Integer companyId);

    int deleteExpired(
            @Param("workspaceId") int workspaceId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    List<Integer> workspaceIdsWithExpired(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);
}

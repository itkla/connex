package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;

/**
 * Persists tenant-scoped business-card import idempotency claims and results.
 */
public interface BusinessCardImportRequestMapper {

    int claim(
            @Param("workspaceId") int workspaceId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") byte[] requestFingerprint);

    BusinessCardImportRecord get(
            @Param("workspaceId") int workspaceId,
            @Param("idempotencyKey") String idempotencyKey);

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

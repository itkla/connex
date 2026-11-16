package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AuditLog;

/** Organization-scoped audit acceptance ledger; no tenant content is read or copied here. */
@Mapper
public interface AiAgentDsrControlMapper {
    /** Resolves one request-serialized client key without consulting mutable subject linkage. */
    AuditLog findAcceptanceByKey(@Param("orgId") int orgId, @Param("requestId") long requestId,
            @Param("idempotencyKey") String idempotencyKey);

    /** Resolves the original accepted operation for authorized progress reads and recovery. */
    AuditLog findAcceptance(@Param("orgId") int orgId, @Param("requestId") long requestId,
            @Param("operationId") String operationId);

    /** Returns durable C2 evidence, if every frozen workspace has completed. */
    AuditLog findCompletion(@Param("orgId") int orgId, @Param("requestId") long requestId,
            @Param("operationId") String operationId);

    /** Bounded recovery scans retain authority for operations interrupted after C1. */
    List<AuditLog> pendingAcceptances(@Param("afterId") int afterId, @Param("limit") int limit);
}

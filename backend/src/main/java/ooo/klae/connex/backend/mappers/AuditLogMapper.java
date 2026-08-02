package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AuditLog;

import java.time.Instant;
import java.util.List;

public interface AuditLogMapper {
    int insert(AuditLog auditLog);

    List<AuditLog> findRecent(@Param("workspaceId") Integer workspaceId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditLog> findByEntity(@Param("workspaceId") Integer workspaceId,
        @Param("entityType") String entityType,
        @Param("entityId") int entityId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditLog> findWorkspaceExport(@Param("workspaceId") Integer workspaceId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditLog> findOrgExport(@Param("orgId") int orgId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditLog> findRecentByOrg(@Param("orgId") int orgId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditLog> findOrgSupportSlice(@Param("orgId") int orgId,
        @Param("since") Instant since,
        @Param("until") Instant until,
        @Param("requestId") String requestId,
        @Param("limit") int limit);

    List<AuditLog> findEntitySupportSlice(@Param("workspaceId") int workspaceId,
        @Param("entityType") String entityType,
        @Param("entityId") int entityId,
        @Param("since") Instant since,
        @Param("until") Instant until,
        @Param("requestId") String requestId,
        @Param("limit") int limit);
}

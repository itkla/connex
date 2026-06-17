package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AuditLog;

import java.util.List;

public interface AuditLogMapper {
    int insert(AuditLog auditLog);

    List<AuditLog> findRecent(@Param("limit") int limit);

    List<AuditLog> findByEntity(@Param("entityType") String entityType,
        @Param("entityId") int entityId,
        @Param("limit") int limit);
}
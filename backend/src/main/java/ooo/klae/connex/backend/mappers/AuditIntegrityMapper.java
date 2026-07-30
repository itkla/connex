package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AuditIntegrityHead;

public interface AuditIntegrityMapper {
    int ensureHead(@Param("scopeType") String scopeType,
        @Param("scopeId") int scopeId,
        @Param("currentHash") String currentHash);

    AuditIntegrityHead lockHead(@Param("scopeType") String scopeType,
        @Param("scopeId") int scopeId);

    int advanceHead(@Param("scopeType") String scopeType,
        @Param("scopeId") int scopeId,
        @Param("expectedNextChainIndex") long expectedNextChainIndex,
        @Param("newNextChainIndex") long newNextChainIndex,
        @Param("currentHash") String currentHash);

    boolean appendOnlyGuardInstalled();
}

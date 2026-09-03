package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupPageRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberRow;

/**
 * Workspace-scoped persistence and reads for the canonical identity collision report.
 */
public interface IdentityCollisionMapper {

    int deleteForWorkspace(@Param("workspaceId") int workspaceId);

    int insertPersonCollisionMembers(
        @Param("workspaceId") int workspaceId,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int insertCompanyCollisionMembers(
        @Param("workspaceId") int workspaceId,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int deletePersonCollisionGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue);

    int insertPersonCollisionGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int deleteCompanyCollisionGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue);

    int insertCompanyCollisionGroup(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int deletePersonCollisionMembershipsForRecord(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    int deleteCompanyCollisionMembershipsForRecord(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId);

    int deletePersonSingletonCollisionMember(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue);

    int deleteCompanySingletonCollisionMember(
        @Param("workspaceId") int workspaceId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue);

    int ensurePersonCollisionPairForRecord(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int ensureCompanyCollisionPairForRecord(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("kind") String kind,
        @Param("normalizedValue") String normalizedValue,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    long countForWorkspace(@Param("workspaceId") int workspaceId);

    List<IdentityCollisionGroupKey> findVisibleGroupKeysAfter(
        @Param("workspaceId") int workspaceId,
        @Param("afterRecordType") String afterRecordType,
        @Param("afterKind") String afterKind,
        @Param("afterNormalizedValue") String afterNormalizedValue,
        @Param("limit") int limit);

    List<IdentityCollisionGroupPageRow> findVisibleGroupPage(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("limit") int limit,
        @Param("offset") long offset);

    List<IdentityCollisionMemberRow> findVisibleMembers(
        @Param("workspaceId") int workspaceId,
        @Param("groups") List<IdentityCollisionGroupKey> groups,
        @Param("afterRecordId") int afterRecordId,
        @Param("memberLimit") int memberLimit);
}

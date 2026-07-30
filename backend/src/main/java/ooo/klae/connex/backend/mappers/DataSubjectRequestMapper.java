package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;

public interface DataSubjectRequestMapper {
    int insert(DataSubjectRequest request);

    int update(DataSubjectRequest request);

    DataSubjectRequest findById(@Param("orgId") int orgId, @Param("requestId") long requestId);

    DataSubjectRequest findByIdForUpdate(
        @Param("orgId") int orgId,
        @Param("requestId") long requestId);

    List<DataSubjectRequest> findByOrg(@Param("orgId") int orgId,
        @Param("status") String status,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AuditEntryDto> findDisclosureAudit(@Param("orgId") int orgId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds,
        @Param("limit") int limit);

    long countDisclosureAudit(@Param("orgId") int orgId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);
}

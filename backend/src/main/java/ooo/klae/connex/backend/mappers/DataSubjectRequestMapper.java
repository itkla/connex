package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ActivityDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AttachmentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.CustomFieldValueDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.DealAssociationDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.EmploymentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.IntroductionDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RelationshipEdgeDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TagDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TaskDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ThirdPartyProvisionDto;

public interface DataSubjectRequestMapper {
    int insert(DataSubjectRequest request);

    int update(DataSubjectRequest request);

    DataSubjectRequest findById(@Param("orgId") int orgId, @Param("requestId") long requestId);

    List<DataSubjectRequest> findByOrg(@Param("orgId") int orgId,
        @Param("status") String status,
        @Param("limit") int limit,
        @Param("offset") int offset);

    boolean subjectPersonInOrg(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    PersonDto findDisclosurePerson(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<TagDto> findDisclosureTags(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<CustomFieldValueDto> findDisclosureCustomFields(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<ActivityDto> findDisclosureActivities(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<NoteDto> findDisclosureNotes(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<TaskDto> findDisclosureTasks(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<AttachmentDto> findDisclosureAttachments(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<EmploymentDto> findDisclosureEmployment(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<RelationshipEdgeDto> findDisclosureEdges(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<DealAssociationDto> findDisclosureDeals(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<IntroductionDto> findDisclosureIntroductions(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<ThirdPartyProvisionDto> findDisclosureProvisions(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    List<AuditEntryDto> findDisclosureAudit(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("limit") int limit);

    long countDisclosureAudit(@Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);
}

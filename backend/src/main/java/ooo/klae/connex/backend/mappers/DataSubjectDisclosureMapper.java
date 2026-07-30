package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ActivityDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AttachmentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.CustomFieldValueDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.DealAssociationDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.EmploymentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.IntroductionDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonIdentityDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RelationshipEdgeDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TagDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TaskDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ThirdPartyProvisionDto;

public interface DataSubjectDisclosureMapper {
    boolean subjectPersonExists(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    Integer lockSubjectPersonForShare(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId);

    PersonDto findPerson(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<PersonIdentityDto> findIdentities(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<TagDto> findTags(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<CustomFieldValueDto> findCustomFields(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<ActivityDto> findActivities(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<NoteDto> findNotes(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<TaskDto> findTasks(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<AttachmentDto> findAttachments(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<EmploymentDto> findEmployment(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<RelationshipEdgeDto> findEdges(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<DealAssociationDto> findDeals(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<IntroductionDto> findIntroductions(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);

    List<ThirdPartyProvisionDto> findProvisions(@Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("workspaceIds") List<Integer> workspaceIds);
}

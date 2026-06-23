package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.User;
import java.util.List;

/**
 * mapper interface for {@code Deal} persistence.
 * SQL is defined in {@code resources/mappers/DealMapper.xml}.
 * Used by {@code DealService}.
 */

public interface DealMapper {
    List<Deal> getAllDeals(int workspaceId);
    List<Deal> getDealsByPipelineId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);
    List<Deal> getDealsByStageId(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    List<Deal> getDealsByCompanyId(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    List<Deal> getDealsByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Deal> getDealsByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    Deal getDealById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Deal> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Deal deal);
    int update(Deal deal);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    String getStageOutcome(int stageId);

    Integer getLastNormalStageId(int pipelineId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int insertTags(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("tagIds") List<Integer> tagIds
    );

    List<DealPerson> getDealPeopleByDealId(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId
    );
    int addPerson(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId,
        @Param("role") String role
    );
    int updatePersonRole(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId,
        @Param("role") String role
    );
    int removePerson(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId
    );
    int clearPeople(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);

    int updateOwner(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("ownerId") Integer ownerId
    );
    List<User> getCollaborators(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int clearCollaborators(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int removeCollaborator(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("userId") int userId
    );
    int insertCollaborators(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("userIds") List<Integer> userIds
    );
}

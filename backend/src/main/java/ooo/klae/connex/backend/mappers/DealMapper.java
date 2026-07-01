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
    /** Deals are owned-only already; mirrors the person/company method so bulk write-scoping is uniform. */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Deal> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    /** id + name + company for every deal in the workspace; for import dedup (normalized in the service). */
    List<Deal> getDealsForDedup(int workspaceId);
    /** Deals in the workspace with the given ids (workspace-scoped); for export of a selected view. */
    List<Deal> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    int insert(Deal deal);
    /** Bulk-insert deals in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Deal> deals);
    int update(Deal deal);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Deal ids in a stage, in board order (position, then id), for renumbering a column on a move. */
    List<Integer> getDealIdsInStageOrdered(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** The next free tail position in a stage column ({@code MAX(position)+1}, or 0 when empty). */
    int nextDealPosition(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** Sets a single deal's manual sort position within its stage column. */
    int setPosition(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("position") int position);

    String getStageOutcome(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);

    Integer getLastNormalStageId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);

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

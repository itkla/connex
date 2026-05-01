package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import java.util.List;

/**
 * mapper interface for {@code Deal} persistence.
 * SQL is defined in {@code resources/mappers/DealMapper.xml}.
 * Used by {@code DealService}.
 */

public interface DealMapper {
    List<Deal> getAllDeals();
    List<Deal> getDealsByPipelineId(int pipelineId);
    List<Deal> getDealsByStageId(int stageId);
    List<Deal> getDealsByCompanyId(int companyId);
    List<Deal> getDealsByPersonId(int personId);
    List<Deal> getDealsByTagId(int tagId);
    Deal getDealById(int id);
    int insert(Deal deal);
    int update(Deal deal);
    int delete(int id);

    int addTag(@Param("dealId") int dealId, @Param("tagId") int tagId);
    int removeTag(@Param("dealId") int dealId, @Param("tagId") int tagId);
    int clearTags(int dealId);
    int insertTags(@Param("dealId") int dealId, @Param("tagIds") List<Integer> tagIds);

    List<DealPerson> getDealPeopleByDealId(int dealId);
    int addPerson(@Param("dealId") int dealId, @Param("personId") int personId, @Param("role") String role);
    int updatePersonRole(@Param("dealId") int dealId, @Param("personId") int personId, @Param("role") String role);
    int removePerson(@Param("dealId") int dealId, @Param("personId") int personId);
    int clearPeople(int dealId);
    int insertPeople(@Param("dealId") int dealId, @Param("people") List<DealPerson> people);
}

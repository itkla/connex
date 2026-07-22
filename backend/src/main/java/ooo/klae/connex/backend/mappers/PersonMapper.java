package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.CompanyEngagementPersonDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;

/**
 * Mapper interface for {@code Person} persistence.
 * SQL is defined in {@code resources/mappers/PersonMapper.xml}.
 * Used by {@code PersonService}.
 */

public interface PersonMapper {
    List<Person> getAllPersons(int workspaceId);
    List<Person> getProcessablePersons(int workspaceId);
    List<Person> getPersonsForNetworkReport(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);
    List<RelationshipScoreAggregateDto> getRelationshipScoreAggregates(
            @Param("workspaceId") int workspaceId,
            @Param("reference") LocalDateTime reference);
    List<Person> getPersonsByCompanyId(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") Integer limit);
    List<CompanyEngagementPersonDto> getCompanyEngagementPeople(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") int limit);
    List<Person> getPersonsByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    List<Person> getPersonsByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    Person getPersonById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Person getOwnedPersonByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Integer> getProcessablePersonIds(@Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids);
    List<Person> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Person> getPersonsByCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** True only when the workspace OWNS the contact (excludes records merely shared in); for write scoping. */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Person> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    /** Existing contacts in the workspace whose email matches one of the given (normalized) emails; for import dedup. */
    List<Person> findByEmails(@Param("workspaceId") int workspaceId, @Param("emails") List<String> emails);
    List<Person> getPersonsPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope,
            @Param("limit") int limit, @Param("offset") int offset);
    long countPersons(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies,
            @Param("titles") List<String> titles, @Param("noCompany") boolean noCompany,
            @Param("memberScope") MemberScope memberScope);
    /** CSV export using the browser filters and member scope, excluding suspended contacts. */
    List<Person> getPersonsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope);
    /** Ids using the browser's filters and member scope; backs "select all matching". */
    List<Integer> getPersonIdsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("memberScope") MemberScope memberScope,
            @Param("limit") int limit);
    List<String> distinctCompanies(int workspaceId);
    List<String> distinctTitles(int workspaceId);
    boolean hasPersonWithoutCompany(int workspaceId);
    List<FacetCount> countsByOwner(@Param("workspaceId") int workspaceId);
    /** Ids of contacts the team has engaged (has any activity, note, or task), used as warm-intro entry points. */
    List<Integer> getEngagedPersonIds(int workspaceId);
    int insert(Person person);
    /** Bulk-insert contacts in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Person> persons);
    int update(Person person);
    int updateOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("ownerId") Integer ownerId);
    int updateImageUrlIfCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentImageUrl") String currentImageUrl,
        @Param("imageUrl") String imageUrl);
    /** Targeted update of the engine-evaluation opt-outs; a {@code null} flag is left unchanged. */
    int updateEvaluationExclusions(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("riskExcluded") Boolean riskExcluded,
        @Param("introExcluded") Boolean introExcluded
    );
    int updateProcessingRestrictions(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("suspended") boolean suspended,
        @Param("provisionCeased") boolean provisionCeased
    );
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Clears contact ownership held by one member within one workspace. */
    void clearMemberOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /** Clears contact ownership held by a user across every workspace. */
    void clearOwnershipAnywhere(@Param("userId") int userId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagIds") List<Integer> tagIds);
}

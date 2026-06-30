package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Person;
import java.util.List;

/**
 * Mapper interface for {@code Person} persistence.
 * SQL is defined in {@code resources/mappers/PersonMapper.xml}.
 * Used by {@code PersonService}.
 */

public interface PersonMapper {
    List<Person> getAllPersons(int workspaceId);
    List<Person> getPersonsByCompanyId(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    List<Person> getPersonsByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    List<Person> getPersonsByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    Person getPersonById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Person> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    /** Existing contacts in the workspace whose email matches one of the given (normalized) emails; for import dedup. */
    List<Person> findByEmails(@Param("workspaceId") int workspaceId, @Param("emails") List<String> emails);
    List<Person> getPersonsPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("limit") int limit, @Param("offset") int offset);
    long countPersons(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies,
            @Param("titles") List<String> titles, @Param("noCompany") boolean noCompany);
    /** Same filter predicates as {@code getPersonsPage} but unpaginated, for sorts computed in Java (warmth). */
    List<Person> getPersonsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany);
    List<String> distinctCompanies(int workspaceId);
    List<String> distinctTitles(int workspaceId);
    boolean hasPersonWithoutCompany(int workspaceId);
    /** Ids of contacts the team has engaged (has any activity, note, or task), used as warm-intro entry points. */
    List<Integer> getEngagedPersonIds(int workspaceId);
    int insert(Person person);
    /** Bulk-insert contacts in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Person> persons);
    int update(Person person);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int addTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("tagIds") List<Integer> tagIds);
}

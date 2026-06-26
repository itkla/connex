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
    int insert(Person person);
    int update(Person person);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int addTag(@Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("personId") int personId, @Param("tagId") int tagId);
    int clearTags(int personId);
    int insertTags(@Param("personId") int personId, @Param("tagIds") List<Integer> tagIds);
}

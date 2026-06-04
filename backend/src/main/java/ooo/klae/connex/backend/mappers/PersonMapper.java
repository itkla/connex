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
    List<Person> getAllPersons();
    List<Person> getPersonsByCompanyId(int companyId);
    List<Person> getPersonsByTagId(int tagId);
    List<Person> getPersonsByDealId(int dealId);
    Person getPersonById(int id);
    List<Person> search(String query);
    List<Person> getPersonsPage(@Param("query") String query, @Param("sort") String sort, @Param("dir") String dir,
            @Param("companies") List<String> companies, @Param("titles") List<String> titles,
            @Param("noCompany") boolean noCompany, @Param("limit") int limit, @Param("offset") int offset);
    long countPersons(@Param("query") String query, @Param("companies") List<String> companies,
            @Param("titles") List<String> titles, @Param("noCompany") boolean noCompany);
    List<String> distinctCompanies();
    List<String> distinctTitles();
    boolean hasPersonWithoutCompany();
    int insert(Person person);
    int update(Person person);
    int delete(int id);

    int addTag(@Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("personId") int personId, @Param("tagId") int tagId);
    int clearTags(int personId);
    int insertTags(@Param("personId") int personId, @Param("tagIds") List<Integer> tagIds);
}

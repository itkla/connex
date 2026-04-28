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
    int insert(Person person);
    int update(Person person);
    int delete(int id);

    int addTag(@Param("personId") int personId, @Param("tagId") int tagId);
    int removeTag(@Param("personId") int personId, @Param("tagId") int tagId);
}

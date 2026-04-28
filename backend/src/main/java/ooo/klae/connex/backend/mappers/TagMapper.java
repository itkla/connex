package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Tag;
import java.util.List;

/**
 * Mapper interface for {@code Tag} persistence and tag-association reads.
 * SQL is defined in {@code resources/mappers/TagMapper.xml}.
 * Used by {@code TagService} and entity services that need to read tags for a record.
 */

public interface TagMapper {
    List<Tag> getAllTags();
    Tag getTagById(int id);
    Tag getTagByName(String name);
    int insert(Tag tag);
    int update(Tag tag);
    int delete(int id);

    List<Tag> getTagsByPersonId(int personId);
    List<Tag> getTagsByCompanyId(int companyId);
    List<Tag> getTagsByDealId(int dealId);
}

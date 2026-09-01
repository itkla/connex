package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Tag;
import java.util.List;
import java.util.Map;

/**
 * Mapper interface for {@code Tag} persistence and tag-association reads.
 * SQL is defined in {@code resources/mappers/TagMapper.xml}.
 * Used by {@code TagService} and entity services that need to read tags for a record.
 */

public interface TagMapper {
    List<Tag> getAllTags(int workspaceId);
    Tag getTagById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Tag getTagByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Tag getTagByName(@Param("workspaceId") int workspaceId, @Param("name") String name);
    List<Tag> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(Tag tag);
    int update(Tag tag);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<Tag> getTagsByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Tag> getTagsByCompanyId(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    List<Tag> getTagsByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Tag> getTagsByAttachmentId(@Param("workspaceId") int workspaceId, @Param("attachmentId") int attachmentId);

    /** All (entityId, name) tag pairs for contacts in the workspace; for bulk export. */
    List<Map<String, Object>> getPersonTagNames(int workspaceId);
    /** All (entityId, name) tag pairs for companies in the workspace; for bulk export. */
    List<Map<String, Object>> getCompanyTagNames(int workspaceId);
    /** All (entityId, name) tag pairs for deals in the workspace; for bulk export. */
    List<Map<String, Object>> getDealTagNames(int workspaceId);
}

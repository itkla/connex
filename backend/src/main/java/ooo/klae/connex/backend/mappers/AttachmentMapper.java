package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.FacetCount;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper interface for {@code Attachment} persistence.
 */

public interface AttachmentMapper {
    List<Attachment> getByEntity(@Param("entityType") String entityType, @Param("entityId") int entityId);
    List<Attachment> getAll();
    Attachment getById(int id);
    List<Attachment> search(String query);
    int insert(Attachment attachment);
    int delete(int id);
    int deleteByEntity(@Param("entityType") String entityType, @Param("entityId") int entityId);
    List<Attachment> getPage(@Param("query") String query, @Param("sort") String sort,
        @Param("types") List<String> types, @Param("kinds") List<String> kinds,
        @Param("tagIds") List<Integer> tagIds, @Param("orphaned") Boolean orphaned,
        @Param("limit") int limit, @Param("offset") int offset);
    long countPage(@Param("query") String query, @Param("types") List<String> types,
        @Param("kinds") List<String> kinds, @Param("tagIds") List<Integer> tagIds,
        @Param("orphaned") Boolean orphaned);
    List<FacetCount> countsBySource();
    List<FacetCount> countsByKind();
    List<FacetCount> countsByTag();
    long countOrphaned();
    long totalCount();
    long totalSize();

    int addTag(@Param("attachmentId") int attachmentId, @Param("tagId") int tagId);
    int removeTag(@Param("attachmentId") int attachmentId, @Param("tagId") int tagId);
    int clearTags(@Param("attachmentId") int attachmentId);
    int insertTags(@Param("attachmentId") int attachmentId, @Param("tagIds") List<Integer> tagIds);
}
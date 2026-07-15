package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.FacetCount;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper interface for {@code Attachment} persistence.
 */

public interface AttachmentMapper {
    List<Attachment> getByEntity(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType, @Param("entityId") int entityId);
    List<Attachment> getAll(int workspaceId);
    Attachment getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Attachment getByUrl(@Param("workspaceId") int workspaceId, @Param("url") String url);
    int countUrl(@Param("workspaceId") int workspaceId, @Param("url") String url);
    int countUrlInOtherWorkspaces(@Param("workspaceId") int workspaceId, @Param("url") String url);
    List<Attachment> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Attachment attachment);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int deleteByEntity(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType, @Param("entityId") int entityId);
    List<Attachment> getPage(@Param("workspaceId") int workspaceId, @Param("query") String query, @Param("sort") String sort,
        @Param("types") List<String> types, @Param("kinds") List<String> kinds,
        @Param("tagIds") List<Integer> tagIds, @Param("orphaned") Boolean orphaned,
        @Param("limit") int limit, @Param("offset") int offset);
    long countPage(@Param("workspaceId") int workspaceId, @Param("query") String query, @Param("types") List<String> types,
        @Param("kinds") List<String> kinds, @Param("tagIds") List<Integer> tagIds,
        @Param("orphaned") Boolean orphaned);
    List<FacetCount> countsBySource(int workspaceId);
    List<FacetCount> countsByKind(int workspaceId);
    List<FacetCount> countsByTag(int workspaceId);
    long countOrphaned(int workspaceId);
    long totalCount(int workspaceId);
    long totalSize(int workspaceId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("attachmentId") int attachmentId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("attachmentId") int attachmentId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("attachmentId") int attachmentId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("attachmentId") int attachmentId, @Param("tagIds") List<Integer> tagIds);

    /**
     * Nulls the uploader reference on every attachment a user uploaded.
     * Offboarding replacement for the {@code attachment.uploaded_by_id}
     * ON DELETE SET NULL (#440 increment 3).
     */
    void clearUploaderAnywhere(@Param("userId") int userId);
}

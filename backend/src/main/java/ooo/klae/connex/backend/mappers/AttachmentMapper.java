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
    List<Attachment> getAll(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);
    Attachment getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Attachment getCreatedById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Attachment getMetadataById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Attachment getMetadataByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Integer> getVisibleIdsIn(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids,
        @Param("currentUserId") int currentUserId
    );
    Attachment getByUrl(
        @Param("workspaceId") int workspaceId,
        @Param("url") String url,
        @Param("currentUserId") int currentUserId);
    Attachment getMetadataByUrl(@Param("workspaceId") int workspaceId, @Param("url") String url);
    List<Attachment> getAssistantSessionAttachments(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);
    List<Attachment> getAssistantSessionAttachmentsByIds(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("ids") List<Integer> ids,
        @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);
    Attachment getAssistantSessionAttachment(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);
    int countAssistantSessionAttachments(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);
    int countUrl(@Param("workspaceId") int workspaceId, @Param("url") String url);
    int countUrlInOtherWorkspaces(@Param("workspaceId") int workspaceId, @Param("url") String url);
    /** Locks every same-workspace attachment reference for an exact URL. */
    List<Integer> lockIdsByUrl(@Param("workspaceId") int workspaceId, @Param("url") String url);
    /** Distinct identities referenced by eligible workspace attachments before name filtering. */
    List<Integer> getQueryUserIds(@Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId, @Param("includeUploaders") boolean includeUploaders,
        @Param("includeTargets") boolean includeTargets);
    List<Attachment> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("currentUserId") int currentUserId, @Param("userFactsJson") String userFactsJson);
    int insert(Attachment attachment);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int deleteByEntity(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType, @Param("entityId") int entityId);
    List<Attachment> getPage(@Param("workspaceId") int workspaceId, @Param("currentUserId") int currentUserId,
        @Param("query") String query, @Param("sort") String sort,
        @Param("types") List<String> types, @Param("kinds") List<String> kinds,
        @Param("tagIds") List<Integer> tagIds, @Param("orphaned") Boolean orphaned,
        @Param("limit") int limit, @Param("offset") int offset, @Param("userFactsJson") String userFactsJson);
    long countPage(@Param("workspaceId") int workspaceId, @Param("currentUserId") int currentUserId,
        @Param("query") String query, @Param("types") List<String> types,
        @Param("kinds") List<String> kinds, @Param("tagIds") List<Integer> tagIds,
        @Param("orphaned") Boolean orphaned, @Param("userFactsJson") String userFactsJson);
    List<FacetCount> countsBySource(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);
    List<FacetCount> countsByKind(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);
    List<FacetCount> countsByTag(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);
    long countOrphaned(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId, @Param("userFactsJson") String userFactsJson);
    long totalCount(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);
    long totalSize(
        @Param("workspaceId") int workspaceId,
        @Param("currentUserId") int currentUserId);

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

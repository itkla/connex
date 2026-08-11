package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.RecordComment;

/** Serialized immutable comment or retained redaction tombstone. */
public record RecordCommentDto(
        long id,
        long threadId,
        UserReferenceDto author,
        String content,
        LocalDateTime createdAt,
        LocalDateTime deletedAt,
        Integer deletedByUserId,
        List<EntityReference> references,
        List<RecordCommentReactionDto> reactions) {

    /** Maps a persisted comment into its API representation. */
    public static RecordCommentDto from(RecordComment comment) {
        UserReferenceDto author = comment.getAuthorUserId() == null
                || comment.getAuthorDisplayName() == null
            ? null
            : new UserReferenceDto(
                comment.getAuthorUserId(),
                comment.getAuthorDisplayName(),
                comment.getAuthorProfilePictureUrl());
        return new RecordCommentDto(
            comment.getId(),
            comment.getThreadId(),
            author,
            comment.getContent(),
            comment.getCreatedAt(),
            comment.getDeletedAt(),
            comment.getDeletedByUserId(),
            comment.getReferences(),
            comment.getReactions().stream().map(RecordCommentReactionDto::from).toList());
    }
}

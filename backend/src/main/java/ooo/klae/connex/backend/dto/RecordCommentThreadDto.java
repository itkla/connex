package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import ooo.klae.connex.backend.beans.RecordCommentThread;

/** Serialized record comment thread with comments ordered from oldest to newest. */
public record RecordCommentThreadDto(
        long id,
        String targetType,
        int targetId,
        int createdByUserId,
        String state,
        Integer resolvedByUserId,
        LocalDateTime resolvedAt,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<RecordCommentDto> comments) {

    /** Maps a hydrated thread into its API representation. */
    public static RecordCommentThreadDto from(RecordCommentThread thread) {
        return new RecordCommentThreadDto(
            thread.getId(),
            thread.getTargetType(),
            thread.getTargetId(),
            thread.getCreatedByUserId(),
            thread.getState(),
            thread.getResolvedByUserId(),
            thread.getResolvedAt(),
            thread.getVersion(),
            thread.getCreatedAt(),
            thread.getUpdatedAt(),
            thread.getComments().stream().map(RecordCommentDto::from).toList());
    }
}

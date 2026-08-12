package ooo.klae.connex.backend.dto;

/** Open comment-thread count for one currently visible target. */
public record RecordCommentIndicatorDto(int targetId, long openThreads) {
}

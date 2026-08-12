package ooo.klae.connex.backend.dto;

/** Viewer-safe identity and membership state for one assistant session participant. */
public record AiChatParticipantDto(
        int userId,
        String displayName,
        String profilePictureUrl,
        String role,
        String status,
        boolean currentUser) {
}

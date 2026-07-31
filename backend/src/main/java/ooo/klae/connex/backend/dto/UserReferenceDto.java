package ooo.klae.connex.backend.dto;

/**
 * Display reference for an active workspace member.
 * @param id application user id
 * @param displayName current display name
 * @param profilePictureUrl current profile picture URL, or {@code null}
 */
public record UserReferenceDto(int id, String displayName, String profilePictureUrl) {
}

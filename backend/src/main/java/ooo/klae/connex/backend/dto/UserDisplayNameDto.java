package ooo.klae.connex.backend.dto;

/**
 * Control-plane display label for an application user.
 * @param id application user id
 * @param displayName current display name
 */
public record UserDisplayNameDto(int id, String displayName) {
}

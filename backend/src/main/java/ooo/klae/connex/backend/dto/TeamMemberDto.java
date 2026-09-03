package ooo.klae.connex.backend.dto;

/**
 * Team seat with a control-plane-hydrated member label.
 *
 * @param userId workspace user id
 * @param name current display name
 * @param role team role
 */
public record TeamMemberDto(int userId, String name, String role) {
}

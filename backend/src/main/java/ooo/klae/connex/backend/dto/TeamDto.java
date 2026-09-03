package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Workspace team with its current seats.
 *
 * @param id team id
 * @param name team name
 * @param description optional description
 * @param managerUserId optional manager user id
 * @param members current team seats
 * @param archivedAt archive timestamp, or null while active
 */
public record TeamDto(
        int id,
        String name,
        String description,
        Integer managerUserId,
        List<TeamMemberDto> members,
        String archivedAt) {

    /** Defensively copies the seat collection exposed by the API. */
    public TeamDto {
        members = List.copyOf(members);
    }
}

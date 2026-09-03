package ooo.klae.connex.backend.dto;

/**
 * One workspace-owned member of a pair-level duplicate review item.
 *
 * @param recordId record identifier
 * @param name current display name
 * @param companyName current visible employer name for a person, otherwise {@code null}
 * @param ownerId current workspace owner identifier, when assigned
 * @param ownedByActiveWorkspace whether the active workspace owns this record rather than seeing a share
 */
public record DuplicateReviewMemberDto(
        int recordId,
        String name,
        String companyName,
        Integer ownerId,
        boolean ownedByActiveWorkspace) {
}

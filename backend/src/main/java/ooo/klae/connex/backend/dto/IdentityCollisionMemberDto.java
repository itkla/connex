package ooo.klae.connex.backend.dto;

/**
 * One visible record participating in a canonical identity collision.
 * @param recordId workspace-owned record ID
 * @param recordName current record name
 */
public record IdentityCollisionMemberDto(int recordId, String recordName) {
}

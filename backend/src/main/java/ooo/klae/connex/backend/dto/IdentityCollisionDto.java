package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One visible cross-record canonical identity collision.
 * @param recordType person or company
 * @param kind canonical identity kind
 * @param normalizedValue canonical colliding value
 * @param collisionSize number of currently visible members
 * @param rebuiltAt collision artifact rebuild timestamp
 * @param members bounded sample of the visible records participating in the collision, shorter
 *     than {@code collisionSize} when the group exceeds the per-group member bound
 */
public record IdentityCollisionDto(
        String recordType,
        String kind,
        String normalizedValue,
        int collisionSize,
        LocalDateTime rebuiltAt,
        List<IdentityCollisionMemberDto> members) {
}

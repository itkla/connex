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
 * @param members bounded first page of the visible records participating in the collision
 * @param membersTruncated whether the group holds more visible members than {@code members}
 *     carries, in which case the remainder is reachable through the group member endpoint
 */
public record IdentityCollisionDto(
        String recordType,
        String kind,
        String normalizedValue,
        int collisionSize,
        LocalDateTime rebuiltAt,
        List<IdentityCollisionMemberDto> members,
        boolean membersTruncated) {
}

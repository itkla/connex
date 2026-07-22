package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Workspace-owned suppression entry.
 * @param id suppression id
 * @param scope suppression scope marker
 * @param channel contact channel
 * @param address normalized channel identifier
 * @param personId optional person id
 * @param reason suppression reason
 * @param note optional operator note
 * @param createdById creator id
 * @param createdAt creation timestamp
 */
public record SuppressionEntryDto(
        int id,
        String scope,
        String channel,
        String address,
        Integer personId,
        String reason,
        String note,
        Integer createdById,
        LocalDateTime createdAt) {
}

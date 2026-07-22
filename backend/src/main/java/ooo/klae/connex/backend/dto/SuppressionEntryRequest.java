package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * New workspace-owned suppression entry.
 * @param scope workspace or global marker within the owning workspace
 * @param channel contact channel
 * @param address channel identifier to normalize
 * @param personId optional owned person id
 * @param reason suppression reason
 * @param note optional operator note
 */
public record SuppressionEntryRequest(
        @NotBlank @Pattern(regexp = "workspace|global") String scope,
        @NotBlank @Pattern(regexp = "email|sms|line|whatsapp") String channel,
        @NotBlank @Size(max = 320) String address,
        @Positive Integer personId,
        @NotBlank @Pattern(regexp = "unsubscribe|hard_bounce|complaint|do_not_contact|manual") String reason,
        @Size(max = 512) String note) {
}

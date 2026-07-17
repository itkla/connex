package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request to create a campaign send against a frozen snapshot version and a message revision.
 * @param snapshotVersion the campaign-local audience snapshot version to send to
 * @param messageId the message to send
 * @param messageVersion the immutable message revision version to send
 * @param purpose the consent purpose to enforce, defaulting to marketing when blank
 * @param scheduledAt an optional scheduled dispatch time
 */
public record CampaignSendRequest(
        @Positive int snapshotVersion,
        @Positive int messageId,
        @Positive int messageVersion,
        @Size(max = 32) String purpose,
        LocalDateTime scheduledAt) {
}

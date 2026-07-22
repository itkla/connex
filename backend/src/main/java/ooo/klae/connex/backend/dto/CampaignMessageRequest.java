package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to create or rename a campaign message.
 * @param name the message name
 * @param channel the delivery channel, defaulting to email when blank
 */
public record CampaignMessageRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 16) String channel) {
}

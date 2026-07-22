package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A request to push a frozen audience snapshot version to a third-party connector.
 * @param snapshotVersion the frozen snapshot version to export
 * @param connector the connector to push to
 */
public record CampaignAudienceExportRequest(
        @Positive int snapshotVersion,
        @NotBlank @Size(max = 32) String connector) {
}

package ooo.klae.connex.backend.dto;

import tools.jackson.databind.JsonNode;

/** Safe human-review representation of one pending assistant write proposal. */
public record AiAssistantToolProposalDto(
        int id,
        String tool,
        String tier,
        String status,
        Target target,
        JsonNode arguments) {

    /** Viewer-authorized resolved target without the provider-visible resource handle. */
    public record Target(
            String kind,
            int id,
            String name) {
    }
}

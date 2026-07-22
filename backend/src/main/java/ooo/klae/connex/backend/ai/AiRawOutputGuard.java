package ooo.klae.connex.backend.ai;

import tools.jackson.databind.JsonNode;

/**
 * Validates a provider's raw JSON output while it is still masked, i.e. before entity tokens are
 * demasked to real values. Features whose grounding depends on what the model itself typed (for
 * example a report narrative that forbids literal figures and requires figure placeholders) must run
 * that check here, because after demasking a legitimate entity name may contain the very characters
 * the check rejects. The guard sees only the masked JSON structure and never the masking context, so
 * masking confinement is preserved.
 */
@FunctionalInterface
public interface AiRawOutputGuard {

    /** Permits every output; the default when a feature has no pre-demask constraint. */
    AiRawOutputGuard PERMIT_ALL = maskedOutput -> true;

    /**
     * @param maskedOutput the parsed, still-masked provider output object
     * @return whether the output may proceed to demasking and binding
     */
    boolean permits(JsonNode maskedOutput);
}

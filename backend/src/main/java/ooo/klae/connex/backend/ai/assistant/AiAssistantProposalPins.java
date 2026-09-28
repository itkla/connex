package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.PrincipalRequest;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Resolution;
import tools.jackson.databind.JsonNode;

/**
 * The resolution and the members a confirm-tier proposal was reviewed against, pinned when it was
 * prepared and stored beside its request.
 *
 * <p>A proposal names its stage or its owner by the name the model wrote, and approval resolves
 * that name again. Without a pin, a rename or an offboarding between the proposal and the approval
 * could make the same name resolve to a row the member never reviewed. The pin records which row
 * the name resolved to when the card was first shown, and the approval refuses any other.
 *
 * <p>The pins are two additive siblings of the stored proposal: {@code principals}, the principal
 * ids in ascending order, written for every confirm-tier proposal and empty when the write names
 * nobody, and {@code resolution}, holding the resolved {@code field} and {@code id}, written only
 * when the tool resolved a value. A proposal without {@code principals} was stored before pinning
 * and is approved and reviewed exactly as it always was. Every reader that predates the pins reads
 * the stored proposal's {@code tool}, {@code tier}, {@code restrictionEpoch}, {@code target} and
 * {@code request} by name and nothing else, so a pinned proposal still reads, renders, approves
 * and rejects on a build without them exactly as one stored without them.
 *
 * <p>Both pins are identifiers of rows the member's own card already names, and neither reaches
 * the model or a viewer who may not read the proposal's details.
 *
 * @param resolutionField the argument key the resolved value is reviewed under, or {@code null}
 * @param resolutionId the resolved row's identifier, or {@code null} when nothing was resolved
 * @param principalIds the principal ids in ascending order, never {@code null}
 */
record AiAssistantProposalPins(
        String resolutionField, Integer resolutionId, List<Integer> principalIds) {
    static final String RESOLUTION = "resolution";
    static final String PRINCIPALS = "principals";
    private static final String FIELD = "field";
    private static final String ID = "id";

    AiAssistantProposalPins {
        Objects.requireNonNull(principalIds, "Assistant proposal pins name their principals");
        principalIds = List.copyOf(principalIds);
        if ((resolutionField == null) != (resolutionId == null)) {
            throw new IllegalArgumentException("Assistant proposal resolution pin is incomplete");
        }
    }

    /**
     * Pins what one proposal resolved when it was prepared.
     *
     * @param resolution the tool's resolution, or {@code null} when it resolves nothing
     * @param principals the tool's principals
     * @return the pins to store beside the request
     */
    static AiAssistantProposalPins of(Resolution resolution, List<PrincipalRequest> principals) {
        return new AiAssistantProposalPins(
                resolution == null ? null : resolution.field(),
                resolution == null ? null : resolution.id(),
                principalIds(principals));
    }

    /**
     * Reads the pins of one stored proposal.
     *
     * @param root the stored proposal
     * @return the pins, or {@code null} for a proposal stored before pinning
     * @throws IllegalArgumentException when a pin is present but malformed
     */
    static AiAssistantProposalPins read(JsonNode root) {
        JsonNode principals = root.get(PRINCIPALS);
        JsonNode resolution = root.get(RESOLUTION);
        if (principals == null) {
            if (resolution != null) {
                throw new IllegalArgumentException("Assistant proposal pins are invalid");
            }
            return null;
        }
        if (!principals.isArray()) {
            throw new IllegalArgumentException("Assistant proposal pins are invalid");
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonNode principal : principals) {
            int id = positiveInteger(principal);
            if (!ids.isEmpty() && ids.getLast() >= id) {
                throw new IllegalArgumentException("Assistant proposal pins are invalid");
            }
            ids.add(id);
        }
        if (resolution == null) {
            return new AiAssistantProposalPins(null, null, ids);
        }
        JsonNode field = resolution.isObject() ? resolution.get(FIELD) : null;
        if (field == null || !field.isString() || field.asString().isBlank()
                || resolution.size() != 2) {
            throw new IllegalArgumentException("Assistant proposal pins are invalid");
        }
        return new AiAssistantProposalPins(
                field.asString(), positiveInteger(resolution.get(ID)), ids);
    }

    /**
     * Adds the pins to one durable proposal after its request.
     *
     * @param durable the durable proposal being built
     */
    void writeTo(Map<String, Object> durable) {
        if (resolutionId != null) {
            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put(FIELD, resolutionField);
            resolution.put(ID, resolutionId);
            durable.put(RESOLUTION, resolution);
        }
        durable.put(PRINCIPALS, principalIds);
    }

    /**
     * @param resolution the resolution an approval made before its locks, or {@code null}
     * @return whether it is the resolution this proposal was reviewed against
     */
    boolean pins(Resolution resolution) {
        return resolution == null
                ? resolutionId == null
                : resolutionId != null
                        && resolutionId == resolution.id()
                        && resolutionField.equals(resolution.field());
    }

    /**
     * @param principals the principals an approval resolved before its locks
     * @return whether they are exactly the members this proposal was reviewed against
     */
    boolean pins(List<PrincipalRequest> principals) {
        return principalIds.equals(principalIds(principals));
    }

    private static List<Integer> principalIds(List<PrincipalRequest> principals) {
        return principals.stream()
                .map(PrincipalRequest::userId)
                .distinct()
                .sorted()
                .toList();
    }

    private static int positiveInteger(JsonNode value) {
        if (value == null || !value.isInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("Assistant proposal pins are invalid");
        }
        return value.intValue();
    }
}

package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.ReferenceService;
import tools.jackson.databind.JsonNode;

/** Pure projection of authorized member labels into request-local masking candidates. */
public final class AiAssistantMemberIdentifiers {
    private AiAssistantMemberIdentifiers() {
    }

    /** Returns exact, nonblank display labels, falling back to username only when necessary. */
    public static List<String> labels(List<User> members) {
        var labels = new LinkedHashSet<String>();
        for (User member : Objects.requireNonNull(members, "members")) {
            if (member == null) {
                continue;
            }
            String label = member.getDisplayName();
            if (label == null || label.isBlank()) {
                label = member.getUsername();
            }
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    /** Registers the active workspace directory without allocating or serializing member tokens. */
    public static void seed(List<User> members, MaskingContext context) {
        labels(members).forEach(context::registerMemberCandidate);
    }

    /** Registers authored labels from reader-authorized references, independent of current status. */
    public static void seedReferences(List<EntityReference> references, MaskingContext context) {
        if (references != null) {
            references.stream().filter(Objects::nonNull)
                    .filter(reference -> "user".equals(reference.getRefType()))
                    .map(EntityReference::getLabel)
                    .forEach(context::registerMemberCandidate);
        }
    }
    /** Registers all authored occurrences after the existing reader-visibility boundary. */
    public static void seedReferenceText(String readerVisibleContent, MaskingContext context) {
        ReferenceService.memberLabelsFromReaderVisibleContent(readerVisibleContent)
                .forEach(context::registerMemberCandidate);
    }

    /** Captures bounded raw provenance from all textual values in a durable write request. */
    public static List<AiAssistantToolResult.Identifier> inRequest(JsonNode request, MaskingContext context) {
        var labels = new LinkedHashSet<String>();
        collectRequestLabels(request, context, labels);
        return checked(labels.stream().map(value -> new AiAssistantToolResult.Identifier("member", value)).toList());
    }

    private static void collectRequestLabels(JsonNode node, MaskingContext context, LinkedHashSet<String> labels) {
        if (node.isString()) {
            labels.addAll(context.memberCandidatesIn(node.asString()));
        } else if (node.isObject() || node.isArray()) {
            node.forEach(child -> collectRequestLabels(child, context, labels));
        }
    }

    /** Reads optional raw provenance from a server-authored durable envelope, including old rows. */
    public static List<AiAssistantToolResult.Identifier> stored(JsonNode envelope) {
        JsonNode stored = envelope.get("memberIdentifiers");
        if (stored == null) {
            return List.of();
        }
        if (!stored.isArray()) {
            throw new IllegalStateException("Assistant member provenance is invalid");
        }
        var identifiers = new LinkedHashSet<AiAssistantToolResult.Identifier>();
        for (JsonNode identifier : stored) {
            if (!"member".equals(identifier.path("kind").asString()) || !identifier.path("value").isString()) {
                throw new IllegalStateException("Assistant member provenance is invalid");
            }
            identifiers.add(new AiAssistantToolResult.Identifier("member", identifier.get("value").asString()));
        }
        return checked(List.copyOf(identifiers));
    }

    private static List<AiAssistantToolResult.Identifier> checked(List<AiAssistantToolResult.Identifier> identifiers) {
        if (identifiers.size() > 200 || identifiers.stream().anyMatch(identifier ->
                identifier.value().isBlank() || identifier.value().length() > 1_000)) {
            throw new IllegalStateException("Assistant member provenance exceeds its bounds");
        }
        return identifiers;
    }

    /** Declares authored labels from a stored reader-authorized prose snapshot as candidates. */
    public static List<AiAssistantToolResult.Identifier> referenceIdentifiers(String readerVisibleContent) {
        return ReferenceService.memberLabelsFromReaderVisibleContent(readerVisibleContent).stream()
                .map(label -> new AiAssistantToolResult.Identifier("member", label))
                .toList();
    }

}

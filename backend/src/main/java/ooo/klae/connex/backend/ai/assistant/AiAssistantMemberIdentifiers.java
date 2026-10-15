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
        return inRequest(request, context, java.util.Map.of());
    }

    /** Merges raw mentions with exact parsed provenance at each unchanged stored-request field. */
    public static List<AiAssistantToolResult.Identifier> inRequest(JsonNode request, MaskingContext context,
            java.util.Map<String, List<String>> parsedMembers) {
        parsedMembers.forEach((path, values) -> {
            if (!request.at(path).isString()) {
                throw new IllegalStateException("Assistant member provenance field changed during preparation");
            }
        });
        var identifiers = new LinkedHashSet<AiAssistantToolResult.Identifier>();
        collectRequestLabels(request, "", context, parsedMembers, identifiers);
        return checked(List.copyOf(identifiers));
    }

    private static void collectRequestLabels(JsonNode node, String path, MaskingContext context,
            java.util.Map<String, List<String>> parsedMembers,
            LinkedHashSet<AiAssistantToolResult.Identifier> identifiers) {
        if (node.isString()) {
            var recorded = parsedMembers.getOrDefault(path, List.of());
            var matches = context.memberCandidatesIn(node.asString(), recorded);
            if (!matches.containsAll(recorded)) {
                throw new IllegalStateException("Assistant member provenance value changed during preparation");
            }
            matches.forEach(value -> identifiers.add(new AiAssistantToolResult.Identifier("member", value, path)));
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collectRequestLabels(entry.getValue(),
                    path + "/" + pointerSegment(entry.getKey()), context, parsedMembers, identifiers));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectRequestLabels(node.get(index), path + "/" + index, context, parsedMembers, identifiers);
            }
        }
    }

    /** Escapes a source field name without losing its JSON pointer position. */
    static String pointerSegment(String field) {
        return field.replace("~", "~0").replace("/", "~1");
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
            JsonNode path = identifier.get("path");
            if (path != null && (!path.isString() || !path.asString().startsWith("/"))) {
                throw new IllegalStateException("Assistant member provenance path is invalid");
            }
            identifiers.add(new AiAssistantToolResult.Identifier("member", identifier.get("value").asString(),
                    path == null ? null : path.asString()));
        }
        List<AiAssistantToolResult.Identifier> bounded = checked(List.copyOf(identifiers));
        if (!envelope.path("request").isObject()) {
            return bounded;
        }
        var legacy = new MaskingContext();
        bounded.stream().filter(identifier -> identifier.path() == null).forEach(identifier -> identifier.seed(legacy));
        var byValue = inRequest(envelope.get("request"), legacy).stream().collect(java.util.stream.Collectors.groupingBy(
                AiAssistantToolResult.Identifier::value));
        return checked(bounded.stream().flatMap(identifier -> identifier.path() == null
                ? byValue.getOrDefault(identifier.value(), List.of(identifier)).stream()
                : java.util.stream.Stream.of(identifier)).distinct().toList());
    }

    /** Restores only the originating suggestion's provenance after an exact persisted-text click. */
    public static List<AiAssistantToolResult.Identifier> suggestionIdentifiers(JsonNode answer, String message) {
        JsonNode suggestions = answer.get("suggestions");
        if (suggestions == null || !suggestions.isArray()) {
            return List.of();
        }
        var members = new LinkedHashSet<AiAssistantToolResult.Identifier>();
        for (int index = 0; index < suggestions.size(); index++) {
            JsonNode suggestion = suggestions.get(index);
            if (suggestion.isString() && message.equals(suggestion.asString())) {
                String path = "/suggestions/" + index;
                stored(answer).stream().filter(identifier -> path.equals(identifier.path()))
                        .map(identifier -> new AiAssistantToolResult.Identifier("member", identifier.value()))
                        .forEach(members::add);
            }
        }
        return checked(List.copyOf(members));
    }

    private static List<AiAssistantToolResult.Identifier> checked(List<AiAssistantToolResult.Identifier> identifiers) {
        if (identifiers.size() > 200 || identifiers.stream().anyMatch(identifier ->
                identifier.value().isBlank() || !ReferenceService.memberLabelWithinLimit(identifier.value()))) {
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

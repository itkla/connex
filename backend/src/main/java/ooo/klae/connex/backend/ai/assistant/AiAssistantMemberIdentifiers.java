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
        var identifiers = new LinkedHashSet<AiAssistantToolResult.Identifier>();
        collectRequestLabels(request, "", context, identifiers);
        return checked(List.copyOf(identifiers));
    }

    private static void collectRequestLabels(JsonNode node, String path, MaskingContext context,
            LinkedHashSet<AiAssistantToolResult.Identifier> identifiers) {
        if (node.isString()) {
            context.memberCandidatesIn(node.asString()).forEach(value ->
                    identifiers.add(new AiAssistantToolResult.Identifier("member", value, path)));
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collectRequestLabels(entry.getValue(),
                    path + "/" + pointerSegment(entry.getKey()), context, identifiers));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectRequestLabels(node.get(index), path + "/" + index, context, identifiers);
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

    /** Restores contained raw labels only for an exact click on a persisted suggestion. */
    public static List<AiAssistantToolResult.Identifier> suggestionIdentifiers(JsonNode answer, String message) {
        JsonNode suggestions = answer.get("suggestions");
        if (suggestions == null || !suggestions.isArray() || suggestions.valueStream().noneMatch(
                suggestion -> suggestion.isString() && message.equals(suggestion.asString()))) {
            return List.of();
        }
        MaskingContext context = new MaskingContext();
        stored(answer).forEach(identifier -> identifier.seed(context));
        return context.memberCandidatesIn(message).stream()
                .map(value -> new AiAssistantToolResult.Identifier("member", value)).toList();
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

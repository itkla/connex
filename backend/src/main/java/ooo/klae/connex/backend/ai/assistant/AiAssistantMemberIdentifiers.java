package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.ReferenceService;

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

    /** Declares authored labels from a stored reader-authorized prose snapshot as candidates. */
    public static List<AiAssistantToolResult.Identifier> referenceIdentifiers(String readerVisibleContent) {
        return ReferenceService.memberLabelsFromReaderVisibleContent(readerVisibleContent).stream()
                .map(label -> new AiAssistantToolResult.Identifier("member", label))
                .toList();
    }

}

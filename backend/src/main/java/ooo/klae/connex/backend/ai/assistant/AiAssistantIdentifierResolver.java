package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Resolves one bounded, locally authorized identifier set for an Ask Connex user turn. */
@Service
@RequiredArgsConstructor
public class AiAssistantIdentifierResolver {
    private static final int MAX_IDENTIFIERS = 20;
    private static final int CANDIDATE_PAGE_SIZE = 200;

    private final AiAssistantIdentifierMapper identifierMapper;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;

    /**
     * Resolves visible mentions in bounded pages for the supplied turn text.
     *
     * <p>The mapper returns a superset of the mentions this turn can have: a row whose canonical
     * name is non-ASCII is admitted with no containment test at all, because MySQL cannot reproduce
     * the Java canonical form for it. Candidate volume is therefore a property of the workspace,
     * not of the turn, and must never refuse it — in a workspace whose records are named in a
     * non-ASCII script every visible row is a candidate. Only the gated mention count, measured
     * after the masking gate has decided, is bounded against the turn budget.
     *
     * <p>Each page bounds materialization, not admission. A full page continues the search instead
     * of refusing or silently dropping later matches. An immutable kind/id cursor prevents a
     * rename or removal of earlier rows from shifting later candidates past the next page.
     *
     * <p>The turn is normalized once across all pages. Every candidate is gated against that same
     * projection, and only admitted mentions consume the turn's identifier budget.
     *
     * @param message complete user turn text
     * @return the resources and raw identifiers this turn must bind
     */
    public Resolution resolve(String message) {
        Objects.requireNonNull(message, "message");
        try {
            return resolveConverged(message);
        } catch (MaskingLeakException exception) {
            throw AiAssistantLoopException.malformed("identifier_text_unsafe");
        }
    }

    private Resolution resolveConverged(String message) {
        MaskingEngine.MentionScanText scanText = MaskingEngine.mentionScanText(message);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String orgWorkspaceIdsJson = workspaceScopeControlAccess
                .getForWorkspace(workspaceId)
                .workspaceIdsJson();
        List<AiAssistantIdentifierMention> mentions = new ArrayList<>();
        String afterKind = "";
        int afterId = 0;
        while (true) {
            List<AiAssistantIdentifierMention> candidates = identifierMapper.findMentionedRecords(
                    workspaceId, orgWorkspaceIdsJson, scanText.lookupText(), CANDIDATE_PAGE_SIZE, afterKind, afterId);
            for (AiAssistantIdentifierMention candidate : candidates) {
                if (MaskingEngine.containsIdentifierMention(scanText, identifierValue(candidate))) {
                    mentions.add(candidate);
                    if (mentions.size() > MAX_IDENTIFIERS) {
                        throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
                    }
                }
            }
            if (candidates.size() < CANDIDATE_PAGE_SIZE) {
                break;
            }
            AiAssistantIdentifierMention last = candidates.getLast();
            afterKind = last.getKind();
            afterId = positiveId(last);
        }
        List<AiChatPageContextDto> resources = mentions.stream()
                .map(mention -> new AiChatPageContextDto(
                        mention.getKind(), positiveId(mention)))
                .toList();
        List<Identifier> identifiers = mentions.stream()
                .map(mention -> new Identifier(
                        entityKind(mention.getKind()), identifierValue(mention)))
                .toList();
        return new Resolution(resources, identifiers);
    }

    /** Seeds every resolved name into the request-local masking dictionary. */
    public void seed(Resolution resolution, MaskingContext context) {
        Objects.requireNonNull(resolution, "resolution").identifiers().forEach(identifier ->
                MaskingEngine.maskField(identifier.kind(), identifier.value(), context));
    }

    private static int positiveId(AiAssistantIdentifierMention mention) {
        if (mention.getId() <= 0) {
            throw new IllegalStateException("Assistant identifier lookup returned an invalid id");
        }
        return mention.getId();
    }

    private static String identifierValue(AiAssistantIdentifierMention mention) {
        String value = mention.getValue();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Assistant identifier lookup returned an invalid value");
        }
        return value;
    }

    private static EntityKind entityKind(String kind) {
        return switch (kind) {
            case "person" -> EntityKind.PERSON;
            case "company" -> EntityKind.COMPANY;
            case "deal" -> EntityKind.DEAL;
            default -> throw new IllegalStateException(
                    "Assistant identifier lookup returned an invalid kind");
        };
    }

    /** Durable resource and masking identifier result from one bounded turn lookup. */
    public record Resolution(
            List<AiChatPageContextDto> resources,
            List<Identifier> identifiers) {
        /** Retains immutable snapshots for queue-to-generation replay. */
        public Resolution {
            resources = List.copyOf(resources);
            identifiers = List.copyOf(identifiers);
        }

        /** Returns an empty successful lookup result. */
        public static Resolution empty() {
            return new Resolution(List.of(), List.of());
        }
    }

    /** One raw visible-record value and its stable masking kind. */
    public record Identifier(EntityKind kind, String value) {
        /** Rejects malformed mapper output before it can enter durable metadata. */
        public Identifier {
            Objects.requireNonNull(kind, "kind");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Assistant identifier value is required");
            }
        }
    }
}

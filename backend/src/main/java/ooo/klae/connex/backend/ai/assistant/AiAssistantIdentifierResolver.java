package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
import ooo.klae.connex.backend.services.ReferenceService;

/** Resolves one bounded, locally authorized identifier set for an Ask Connex user turn. */
@Service
@RequiredArgsConstructor
public class AiAssistantIdentifierResolver {
    private static final int MAX_IDENTIFIERS = 20;
    private static final int CANDIDATE_PAGE_SIZE = 200;

    private final AiAssistantIdentifierMapper identifierMapper;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final ReferenceService referenceService;

    /**
     * Resolves visible mentions in bounded pages for the supplied turn text.
     *
     * <p>Current canonical keys select a binary-containment superset of real mentions. Missing
     * or stale bundles and oversized unkeyed names retain the legacy SQL prefilter and can return every non-ASCII name.
     * Candidate volume therefore never refuses a turn: only the Java-gated mention count is
     * bounded against the turn budget. Java retains its original source-boundary checks.
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
        var memberReading = ReferenceService.memberReferenceReading(message);
        if (!memberReading.complete()) {
            throw AiAssistantLoopException.malformed("identifier_text_unsafe");
        }
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
                    workspaceId, orgWorkspaceIdsJson, scanText.literalText(), scanText.labelText(),
                    CANDIDATE_PAGE_SIZE, afterKind, afterId);
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
        List<Identifier> identifiers = new ArrayList<>(mentions.stream()
                .map(mention -> new Identifier(
                        entityKind(mention.getKind()), identifierValue(mention)))
                .toList());
        var memberLabels = new LinkedHashSet<String>();
        AiAssistantMemberIdentifiers.labels(workspaceService.getMembers(workspaceId)).stream()
                .filter(label -> MaskingEngine.containsMemberMention(scanText, label)).forEach(memberLabels::add);
        List<ReferenceService.MemberReference> memberReferences = memberReading.references();
        Set<Set<Integer>> targetGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        var memberIds = new LinkedHashSet<Integer>();
        for (ReferenceService.MemberReference reference : memberReferences) {
            if (targetGroups.add(reference.memberIds())) {
                memberIds.addAll(reference.memberIds());
            }
        }
        Set<Integer> visibleMembers = referenceService.visibleMemberIds(workspaceId, memberIds);
        var memberVisibility = new IdentityHashMap<Set<Integer>, Boolean>();
        for (ReferenceService.MemberReference reference : memberReferences) {
            if (memberVisibility.computeIfAbsent(reference.memberIds(),
                    ids -> ids.stream().anyMatch(visibleMembers::contains))) {
                memberLabels.add(reference.label());
                if (identifiers.size() + memberLabels.size() > MAX_IDENTIFIERS) {
                    throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
                }
            }
        }
        for (String label : memberLabels) {
            identifiers.add(new Identifier(EntityKind.MEMBER, label));
            if (identifiers.size() > MAX_IDENTIFIERS) {
                throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
            }
        }
        return new Resolution(resources, identifiers);
    }

    /** Seeds every resolved name into the request-local masking dictionary. */
    public void seed(Resolution resolution, MaskingContext context) {
        Objects.requireNonNull(resolution, "resolution").identifiers().forEach(identifier -> {
            if (identifier.kind() == EntityKind.MEMBER) {
                context.registerMemberCandidate(identifier.value());
            } else {
                MaskingEngine.maskField(identifier.kind(), identifier.value(), context);
            }
        });
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

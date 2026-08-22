package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
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
    private static final int OVERFLOW_LIMIT = MAX_IDENTIFIERS + 1;

    private final AiAssistantIdentifierMapper identifierMapper;
    private final WorkspaceService workspaceService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;

    /** Resolves exactly one globally bounded visible-record search for the supplied turn text. */
    public Resolution resolve(String message) {
        Objects.requireNonNull(message, "message");
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String orgWorkspaceIdsJson = workspaceScopeControlAccess
                .getForWorkspace(workspaceId)
                .workspaceIdsJson();
        List<AiAssistantIdentifierMention> mentions = identifierMapper.findMentionedRecords(
                workspaceId, orgWorkspaceIdsJson, message, OVERFLOW_LIMIT);
        if (mentions.size() > MAX_IDENTIFIERS) {
            throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
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

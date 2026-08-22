package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.AiGeneratedContentScreen;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.AiChatCitationDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects stored citation metadata through the current viewer's live record visibility. */
@Service
@RequiredArgsConstructor
public class AiChatCitationProjector {
    private final ObjectMapper objectMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final AiChatMapper chatMapper;

    /** Returns authorized citations and messages withheld by current record visibility. */
    public Projection project(
            int workspaceId, List<AiChatMessage> messages) {
        Map<Integer, List<StoredCitation>> storedByMessage = new LinkedHashMap<>();
        Map<Integer, StoredResources> resourcesByMessage = new LinkedHashMap<>();
        Set<RecordKey> requested = new LinkedHashSet<>();
        for (AiChatMessage message : messages) {
            List<StoredCitation> stored = storedCitations(message);
            storedByMessage.put(message.getId(), stored);
            stored.stream()
                    .map(citation -> new RecordKey(citation.kind(), citation.id()))
                    .forEach(requested::add);
            StoredResources resources = storedResources(message);
            resourcesByMessage.put(message.getId(), resources);
            requested.addAll(resources.records());
        }
        Set<RecordKey> visible = visibleRecords(workspaceId, requested);
        Map<Integer, List<AiChatCitationDto>> projected = new LinkedHashMap<>();
        storedByMessage.forEach((messageId, citations) -> projected.put(
                messageId,
                citations.stream()
                        .filter(citation -> visible.contains(
                                new RecordKey(citation.kind(), citation.id())))
                        .map(citation -> new AiChatCitationDto(
                                citation.handle(), citation.kind(), citation.id()))
                        .toList()));
        Set<Integer> withheldMessageIds = new LinkedHashSet<>();
        resourcesByMessage.forEach((messageId, resources) -> {
            if (!resources.valid() || !visible.containsAll(resources.records())) {
                withheldMessageIds.add(messageId);
            }
        });
        return new Projection(Map.copyOf(projected), Set.copyOf(withheldMessageIds));
    }

    /** Returns validated demasked follow-up suggestions only to each turn's original asker. */
    public Map<Integer, List<String>> suggestions(
            int workspaceId,
            int sessionId,
            int userId,
            List<AiChatMessage> messages) {
        Map<Integer, StoredSuggestions> storedByMessage = new LinkedHashMap<>();
        Set<Integer> turnIds = new LinkedHashSet<>();
        for (AiChatMessage message : messages) {
            StoredSuggestions stored = storedSuggestions(message);
            if (stored == null) {
                continue;
            }
            storedByMessage.put(message.getId(), stored);
            turnIds.add(stored.turnId());
        }
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> requesterByTurn = new LinkedHashMap<>();
        for (AiChatTurn turn : chatMapper.listTurnsByIds(
                workspaceId, sessionId, List.copyOf(turnIds))) {
            if (turn.getRequestedByUserId() != null) {
                requesterByTurn.put(turn.getId(), turn.getRequestedByUserId());
            }
        }
        Map<Integer, List<String>> projected = new LinkedHashMap<>();
        storedByMessage.forEach((messageId, stored) -> projected.put(
                messageId,
                Objects.equals(requesterByTurn.get(stored.turnId()), userId)
                        ? AiAssistantStepGuard.filterSuggestions(stored.values())
                        : List.of()));
        return Map.copyOf(projected);
    }

    /** Returns validated demasked reasoning only to each turn's original asker. */
    public Map<Integer, String> reasoning(
            int workspaceId,
            int sessionId,
            int userId,
            List<AiChatMessage> messages) {
        Map<Integer, StoredReasoning> storedByMessage = new LinkedHashMap<>();
        Set<Integer> turnIds = new LinkedHashSet<>();
        for (AiChatMessage message : messages) {
            StoredReasoning stored = storedReasoning(message);
            if (stored == null) {
                continue;
            }
            storedByMessage.put(message.getId(), stored);
            turnIds.add(stored.turnId());
        }
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> requesterByTurn = requesterByTurn(
                workspaceId, sessionId, turnIds);
        Map<Integer, String> projected = new LinkedHashMap<>();
        storedByMessage.forEach((messageId, stored) -> {
            if (Objects.equals(requesterByTurn.get(stored.turnId()), userId)) {
                projected.put(messageId, stored.value());
            }
        });
        return Map.copyOf(projected);
    }

    private StoredReasoning storedReasoning(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(message.getStructuredJson());
            JsonNode turnId = metadata.get("turnId");
            JsonNode reasoning = metadata.get("reasoning");
            if (turnId == null || !turnId.isIntegralNumber()
                    || !turnId.canConvertToInt() || turnId.asInt() <= 0
                    || reasoning == null || !reasoning.isString()
                    || reasoning.asString().isBlank()
                    || reasoning.asString().length() > 16_000
                    || AiGeneratedContentScreen.containsPlaceholder(reasoning.asString())
                    || AiGeneratedContentScreen.rejectionReason(reasoning.asString()) != null) {
                return null;
            }
            return new StoredReasoning(turnId.asInt(), reasoning.asString());
        } catch (JacksonException exception) {
            return null;
        }
    }

    private StoredSuggestions storedSuggestions(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(message.getStructuredJson());
            JsonNode turnId = metadata.get("turnId");
            JsonNode suggestions = metadata.get("suggestions");
            if (turnId == null || !turnId.isIntegralNumber()
                    || !turnId.canConvertToInt() || turnId.asInt() <= 0
                    || suggestions == null || !suggestions.isArray()) {
                return null;
            }
            List<String> stored = new ArrayList<>();
            for (JsonNode suggestion : suggestions) {
                if (!suggestion.isString()) {
                    continue;
                }
                stored.add(suggestion.asString());
            }
            return new StoredSuggestions(turnId.asInt(), List.copyOf(stored));
        } catch (JacksonException exception) {
            return null;
        }
    }

    private Map<Integer, Integer> requesterByTurn(
            int workspaceId, int sessionId, Set<Integer> turnIds) {
        Map<Integer, Integer> requesterByTurn = new LinkedHashMap<>();
        for (AiChatTurn turn : chatMapper.listTurnsByIds(
                workspaceId, sessionId, List.copyOf(turnIds))) {
            if (turn.getRequestedByUserId() != null) {
                requesterByTurn.put(turn.getId(), turn.getRequestedByUserId());
            }
        }
        return Map.copyOf(requesterByTurn);
    }

    private List<StoredCitation> storedCitations(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return List.of();
        }
        try {
            JsonNode citations = objectMapper.readTree(message.getStructuredJson()).get("citations");
            if (citations == null || !citations.isArray()) {
                return List.of();
            }
            List<StoredCitation> stored = new ArrayList<>();
            for (JsonNode citation : citations) {
                JsonNode handle = citation.get("handle");
                JsonNode kind = citation.get("kind");
                JsonNode id = citation.get("id");
                if (handle != null && handle.isString()
                        && kind != null && kind.isString() && isRecordKind(kind.asString())
                        && id != null && id.canConvertToInt() && id.asInt() > 0) {
                    stored.add(new StoredCitation(
                            handle.asString(), kind.asString(), id.asInt()));
                }
            }
            return List.copyOf(stored);
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private StoredResources storedResources(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return StoredResources.empty();
        }
        try {
            JsonNode metadata = objectMapper.readTree(message.getStructuredJson());
            JsonNode resources = metadata.get("resources");
            if (resources == null) {
                resources = metadata.get("citations");
            }
            if (resources == null) {
                return StoredResources.empty();
            }
            if (!resources.isArray()) {
                return StoredResources.invalid();
            }
            Set<RecordKey> stored = new LinkedHashSet<>();
            for (JsonNode resource : resources) {
                JsonNode kind = resource.get("kind");
                JsonNode id = resource.get("id");
                if (kind == null || !kind.isString() || !isRecordKind(kind.asString())
                        || id == null || !id.canConvertToInt() || id.asInt() <= 0) {
                    return StoredResources.invalid();
                }
                stored.add(new RecordKey(kind.asString(), id.asInt()));
            }
            return new StoredResources(Set.copyOf(stored), true);
        } catch (JacksonException exception) {
            return StoredResources.invalid();
        }
    }

    private Set<RecordKey> visibleRecords(int workspaceId, Set<RecordKey> requested) {
        List<Integer> personIds = ids(requested, "person");
        List<Integer> companyIds = ids(requested, "company");
        List<Integer> dealIds = ids(requested, "deal");
        Set<RecordKey> visible = new LinkedHashSet<>();
        if (!personIds.isEmpty()) {
            personMapper.getByIds(workspaceId, personIds).stream()
                    .filter(AiChatCitationProjector::isProcessable)
                    .map(person -> new RecordKey("person", person.getId()))
                    .forEach(visible::add);
        }
        if (!companyIds.isEmpty()) {
            companyMapper.getByIds(workspaceId, companyIds).stream()
                    .map(company -> new RecordKey("company", company.getId()))
                    .forEach(visible::add);
        }
        if (!dealIds.isEmpty()) {
            dealMapper.getByIds(workspaceId, dealIds).stream()
                    .map(deal -> new RecordKey("deal", deal.getId()))
                    .forEach(visible::add);
        }
        return Set.copyOf(visible);
    }

    private static List<Integer> ids(Set<RecordKey> requested, String kind) {
        return requested.stream()
                .filter(record -> kind.equals(record.kind()))
                .map(RecordKey::id)
                .toList();
    }

    private static boolean isProcessable(Person person) {
        return person.getArchivedAt() == null
                && person.getSuspendedAt() == null
                && person.getProvisionCeasedAt() == null;
    }

    private static boolean isRecordKind(String kind) {
        return "person".equals(kind) || "company".equals(kind) || "deal".equals(kind);
    }

    private record StoredCitation(String handle, String kind, int id) {
    }

    private record StoredSuggestions(int turnId, List<String> values) {
    }

    private record StoredReasoning(int turnId, String value) {
    }

    private record StoredResources(Set<RecordKey> records, boolean valid) {
        private static StoredResources empty() {
            return new StoredResources(Set.of(), true);
        }

        private static StoredResources invalid() {
            return new StoredResources(Set.of(), false);
        }
    }

    private record RecordKey(String kind, int id) {
    }

    /** Viewer-safe transcript projection derived from one bounded record-visibility pass. */
    public record Projection(
            Map<Integer, List<AiChatCitationDto>> citationsByMessage,
            Set<Integer> withheldMessageIds) {
        /** Retains immutable snapshots at the service boundary. */
        public Projection {
            citationsByMessage = Map.copyOf(citationsByMessage);
            withheldMessageIds = Set.copyOf(withheldMessageIds);
        }
    }
}

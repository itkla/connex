package ooo.klae.connex.backend.ai.assistant;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.AiChatCitationDto;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects stored citation metadata through the current viewer's live record visibility. */
@Service
@RequiredArgsConstructor
public class AiChatCitationProjector {
    private static final int MAX_CITATION_DETAIL_CHARS = 120;
    private static final java.util.regex.Pattern SKILL_KEY =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final java.util.regex.Pattern SKILL_VERSION =
            java.util.regex.Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final int MAX_STORED_INSTANT_CHARS = 64;

    private final ObjectMapper objectMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
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
        Map<RecordKey, VisibleRecord> visible = visibleRecords(workspaceId, requested);
        Map<Integer, List<AiChatCitationDto>> projected = new LinkedHashMap<>();
        storedByMessage.forEach((messageId, citations) -> projected.put(
                messageId,
                citations.stream()
                        .filter(citation -> visible.containsKey(
                                new RecordKey(citation.kind(), citation.id())))
                        .map(citation -> citation(
                                citation,
                                visible.get(new RecordKey(citation.kind(), citation.id()))))
                        .toList()));
        Set<Integer> withheldMessageIds = new LinkedHashSet<>();
        resourcesByMessage.forEach((messageId, resources) -> {
            if (!resources.valid() || !visible.keySet().containsAll(resources.records())) {
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

    private static boolean text(JsonNode value, int maxLength) {
        return value != null && value.isString()
                && !value.asString().isBlank() && value.asString().length() <= maxLength;
    }

    private static String nullableValue(JsonNode value) {
        return value == null || value.isNull() ? null : value.asString();
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

    private Integer storedTurnId(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return null;
        }
        try {
            JsonNode turnId = objectMapper.readTree(message.getStructuredJson()).get("turnId");
            return turnId != null && turnId.isIntegralNumber()
                    && turnId.canConvertToInt() && turnId.asInt() > 0
                    ? turnId.asInt()
                    : null;
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
                            handle.asString(), kind.asString(), id.asInt(),
                            storedObservation(citation.get("observed"))));
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

    private Map<RecordKey, VisibleRecord> visibleRecords(int workspaceId, Set<RecordKey> requested) {
        List<Integer> personIds = ids(requested, "person");
        List<Integer> companyIds = ids(requested, "company");
        List<Integer> dealIds = ids(requested, "deal");
        Map<RecordKey, VisibleRecord> visible = new LinkedHashMap<>();
        if (!personIds.isEmpty()) {
            personMapper.getByIds(workspaceId, personIds).stream()
                    .filter(AiChatCitationProjector::isProcessable)
                    .forEach(person -> visible.put(
                            new RecordKey("person", person.getId()),
                            new VisibleRecord(
                                    safeLabel(person.getName()),
                                    instant(person.getUpdatedAt()),
                                    detail(person.getCompany() == null
                                            ? null
                                            : person.getCompany().getName()))));
        }
        if (!companyIds.isEmpty()) {
            companyMapper.getByIds(workspaceId, companyIds)
                    .forEach(company -> visible.put(
                            new RecordKey("company", company.getId()),
                            new VisibleRecord(
                                    safeLabel(company.getName()),
                                    instant(company.getUpdatedAt()),
                                    detail(company.getIndustry()))));
        }
        if (!dealIds.isEmpty()) {
            List<Deal> deals = dealMapper.getByIds(workspaceId, dealIds);
            Map<Integer, String> stageNames = deals.isEmpty()
                    ? Map.of()
                    : stageNames(workspaceId);
            deals.forEach(deal -> visible.put(
                    new RecordKey("deal", deal.getId()),
                    new VisibleRecord(
                            safeLabel(deal.getName()),
                            instant(deal.getUpdatedAt()),
                            detail(deal.getStageId() == null
                                    ? null
                                    : stageNames.get(deal.getStageId())))));
        }
        return Map.copyOf(visible);
    }

    private Map<Integer, String> stageNames(int workspaceId) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Stage stage : pipelineMapper.getAllStages(workspaceId)) {
            if (stage.getName() != null) {
                names.put(stage.getId(), stage.getName());
            }
        }
        return Map.copyOf(names);
    }

    /**
     * Projects one stored citation through the viewer's live record visibility.
     *
     * <p>The label always comes from the live record, because identity is what the viewer is being
     * shown and must never lag. Freshness and the subtitle come from the snapshot the answering turn
     * recorded, so replaying an old transcript describes the evidence the answer was written
     * against. A message stored before snapshots existed has none, and falls back to the live
     * record while reporting that the values were not observed.
     */
    private static AiChatCitationDto citation(StoredCitation citation, VisibleRecord record) {
        AiChatRecordObservation observed = citation.observed();
        return new AiChatCitationDto(
                citation.handle(), citation.kind(), citation.id(), record.label(),
                observed == null ? record.asOf() : observed.asOf(),
                observed == null ? record.detail() : observed.detail(),
                observed != null);
    }

    /**
     * Reads one stored evidence snapshot, revalidating it on read rather than trusting the row.
     *
     * @param value stored {@code observed} object, or null for a message written before snapshots
     * @return the snapshot, or null when the row carries none or carries an unreadable one
     */
    private static AiChatRecordObservation storedObservation(JsonNode value) {
        if (value == null || !value.isObject()) {
            return null;
        }
        JsonNode asOf = value.get("asOf");
        JsonNode detail = value.get("detail");
        if (asOf == null || detail == null
                || !(asOf.isNull() || text(asOf, MAX_STORED_INSTANT_CHARS))
                || !(detail.isNull() || text(detail, MAX_CITATION_DETAIL_CHARS))) {
            return null;
        }
        String instant = asOf.isNull() ? null : storedInstant(asOf.asString());
        if (!asOf.isNull() && instant == null) {
            return null;
        }
        return new AiChatRecordObservation(instant, detail(nullableValue(detail)));
    }

    /** Re-normalizes a stored ISO-8601 evidence instant, or returns null when it is unreadable. */
    private static String storedInstant(String value) {
        try {
            return Instant.parse(value.strip()).toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Records the freshness and subtitle each cited record shows right now, so the answering turn
     * can store them beside its citation handles.
     *
     * @param workspaceId resolved tenant workspace
     * @param citations handles the answer cited
     * @param resources server-only handle-to-record identities for the turn
     * @return snapshot per cited handle, omitting records that are not currently visible
     */
    public Map<String, AiChatRecordObservation> observe(
            int workspaceId,
            List<String> citations,
            Map<String, AiChatResourceRegistry.ResourceRef> resources) {
        Map<String, RecordKey> keyByHandle = new LinkedHashMap<>();
        for (String handle : citations) {
            AiChatResourceRegistry.ResourceRef resource = resources.get(handle);
            if (resource != null) {
                keyByHandle.put(handle, new RecordKey(resource.kind(), resource.id()));
            }
        }
        if (keyByHandle.isEmpty()) {
            return Map.of();
        }
        Map<RecordKey, VisibleRecord> visible = visibleRecords(
                workspaceId, new LinkedHashSet<>(keyByHandle.values()));
        Map<String, AiChatRecordObservation> observed = new LinkedHashMap<>();
        keyByHandle.forEach((handle, key) -> {
            VisibleRecord record = visible.get(key);
            if (record != null) {
                observed.put(handle, new AiChatRecordObservation(record.asOf(), record.detail()));
            }
        });
        return Map.copyOf(observed);
    }

    private static String safeLabel(String label) {
        return label == null ? "" : label;
    }

    private static String detail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() <= MAX_CITATION_DETAIL_CHARS) {
            return stripped;
        }
        int end = MAX_CITATION_DETAIL_CHARS;
        if (Character.isHighSurrogate(stripped.charAt(end - 1))) {
            end--;
        }
        return stripped.substring(0, end);
    }

    /**
     * Normalizes a stored MySQL {@code DATETIME} into an ISO-8601 instant. Connex persists these
     * columns in UTC without an offset, matching the frontend's {@code parseMysqlDateTime} reader,
     * so an unparsable value yields no freshness rather than a guessed one.
     */
    private static String instant(String storedTimestamp) {
        if (storedTimestamp == null || storedTimestamp.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(storedTimestamp.strip().replace(' ', 'T'))
                    .atOffset(ZoneOffset.UTC)
                    .toInstant()
                    .toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
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

    private record StoredCitation(
            String handle, String kind, int id, AiChatRecordObservation observed) {
    }

    private record StoredSuggestions(int turnId, List<String> values) {
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

    private record VisibleRecord(String label, String asOf, String detail) {
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

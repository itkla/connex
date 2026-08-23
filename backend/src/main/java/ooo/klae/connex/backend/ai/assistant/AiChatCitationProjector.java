package ooo.klae.connex.backend.ai.assistant;

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
import ooo.klae.connex.backend.dto.AiChatAnswerBlockDto;
import ooo.klae.connex.backend.dto.AiChatAnswerDocumentDto;
import ooo.klae.connex.backend.dto.AiChatAnswerRowDto;
import ooo.klae.connex.backend.dto.AiChatCitationDto;
import ooo.klae.connex.backend.dto.AiChatCoverageDto;
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

    /** Returns assistant messages whose originating turn belongs to the current viewer. */
    public Set<Integer> requestedMessageIds(
            int workspaceId,
            int sessionId,
            int userId,
            List<AiChatMessage> messages) {
        Map<Integer, Integer> turnByMessage = new LinkedHashMap<>();
        Set<Integer> turnIds = new LinkedHashSet<>();
        for (AiChatMessage message : messages) {
            Integer turnId = storedTurnId(message);
            if (turnId != null) {
                turnByMessage.put(message.getId(), turnId);
                turnIds.add(turnId);
            }
        }
        if (turnIds.isEmpty()) {
            return Set.of();
        }
        Map<Integer, Integer> requesterByTurn = requesterByTurn(
                workspaceId, sessionId, turnIds);
        Set<Integer> requestedMessageIds = new LinkedHashSet<>();
        turnByMessage.forEach((messageId, turnId) -> {
            if (Objects.equals(requesterByTurn.get(turnId), userId)) {
                requestedMessageIds.add(messageId);
            }
        });
        return Set.copyOf(requestedMessageIds);
    }

    /** Returns one validated native answer document with only viewer-authorized evidence. */
    public AiChatAnswerDocumentDto answerDocument(
            AiChatMessage message, List<AiChatCitationDto> citations) {
        return answerDocument(message, citations, true);
    }

    /** Returns one answer document with requester-only execution counts removed when shared. */
    public AiChatAnswerDocumentDto answerDocument(
            AiChatMessage message,
            List<AiChatCitationDto> citations,
            boolean requester) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return null;
        }
        try {
            JsonNode metadata = objectMapper.readTree(message.getStructuredJson());
            JsonNode turnId = metadata.get("turnId");
            if (turnId == null || !turnId.isIntegralNumber()
                    || !turnId.canConvertToInt() || turnId.asInt() <= 0
                    || metadata.get("blocks") == null || !metadata.get("blocks").isArray()) {
                return null;
            }
            Map<String, AiChatCitationDto> evidenceByHandle = new LinkedHashMap<>();
            for (AiChatCitationDto citation : citations) {
                if (citation != null && evidenceByHandle.putIfAbsent(
                        citation.handle(), citation) != null) {
                    return null;
                }
            }
            List<AiChatAnswerBlockDto> blocks = answerBlocks(
                    metadata.get("blocks"), evidenceByHandle);
            AiChatCoverageDto coverage = coverage(metadata.get("coverage"));
            List<AiChatProgressItemDto> progress = progress(metadata.get("progress"));
            if (blocks.isEmpty() || coverage == null || progress == null) {
                return null;
            }
            return new AiChatAnswerDocumentDto(
                    turnId.asInt(), blocks, coverage,
                    requester ? progress : sharedProgress(progress));
        } catch (JacksonException exception) {
            return null;
        }
    }

    private static List<AiChatAnswerBlockDto> answerBlocks(
            JsonNode values, Map<String, AiChatCitationDto> evidenceByHandle) {
        if (values == null || !values.isArray()
                || values.isEmpty() || values.size() > AiAssistantStepGuard.MAX_BLOCKS) {
            return List.of();
        }
        List<AiChatAnswerBlockDto> blocks = new ArrayList<>();
        for (JsonNode value : values) {
            if (!exactFields(
                    value, Set.of("kind", "title", "body", "items", "rows", "citations"))) {
                return List.of();
            }
            JsonNode kind = value.get("kind");
            JsonNode title = value.get("title");
            JsonNode body = value.get("body");
            JsonNode items = value.get("items");
            JsonNode rows = value.get("rows");
            JsonNode evidence = value.get("citations");
            if (kind == null || !kind.isString()
                    || !AiAssistantStepGuard.BLOCK_KINDS.contains(kind.asString())
                    || !nullableText(title, 200)
                    || !nullableText(body, AiAssistantStepGuard.MAX_BLOCK_CHARS)
                    || items == null || !items.isArray()
                    || items.size() > AiAssistantStepGuard.MAX_BLOCK_ITEMS
                    || rows == null || !rows.isArray()
                    || rows.size() > AiAssistantStepGuard.MAX_BLOCK_ITEMS
                    || evidence == null || !evidence.isArray()
                    || evidence.size() > AiAssistantStepGuard.MAX_BLOCK_CITATIONS
                    || (!rows.isEmpty()
                            && !AiAssistantStepGuard.ROW_BLOCK_KINDS.contains(kind.asString()))
                    || (body.isNull() && items.isEmpty() && rows.isEmpty())) {
                return List.of();
            }
            List<String> projectedItems = new ArrayList<>();
            for (JsonNode item : items) {
                if (!text(item, AiAssistantStepGuard.MAX_BLOCK_ITEM_CHARS)) {
                    return List.of();
                }
                projectedItems.add(item.asString());
            }
            List<AiChatAnswerRowDto> projectedRows = answerRows(rows, evidenceByHandle);
            if (projectedRows == null) {
                return List.of();
            }
            List<AiChatCitationDto> projectedEvidence = evidence(evidence, evidenceByHandle);
            if (projectedEvidence == null) {
                return List.of();
            }
            blocks.add(new AiChatAnswerBlockDto(
                    kind.asString(), nullableValue(title), nullableValue(body),
                    projectedItems, projectedRows, projectedEvidence));
        }
        return List.copyOf(blocks);
    }

    private static List<AiChatAnswerRowDto> answerRows(
            JsonNode values, Map<String, AiChatCitationDto> evidenceByHandle) {
        List<AiChatAnswerRowDto> rows = new ArrayList<>();
        for (JsonNode value : values) {
            if (!exactFields(value, Set.of("label", "value", "detail", "at", "citations"))) {
                return null;
            }
            JsonNode label = value.get("label");
            JsonNode rowValue = value.get("value");
            JsonNode detail = value.get("detail");
            JsonNode at = value.get("at");
            if (!text(label, AiAssistantStepGuard.MAX_ROW_LABEL_CHARS)
                    || !nullableText(rowValue, AiAssistantStepGuard.MAX_ROW_VALUE_CHARS)
                    || !nullableText(detail, AiAssistantStepGuard.MAX_ROW_VALUE_CHARS)
                    || !nullableText(at, AiAssistantStepGuard.MAX_ROW_AT_CHARS)) {
                return null;
            }
            JsonNode evidence = value.get("citations");
            if (evidence == null || !evidence.isArray()
                    || evidence.size() > AiAssistantStepGuard.MAX_BLOCK_CITATIONS) {
                return null;
            }
            List<AiChatCitationDto> projectedEvidence = evidence(evidence, evidenceByHandle);
            if (projectedEvidence == null) {
                return null;
            }
            rows.add(new AiChatAnswerRowDto(
                    label.asString(), nullableValue(rowValue), nullableValue(detail),
                    nullableValue(at), projectedEvidence));
        }
        return List.copyOf(rows);
    }

    private static List<AiChatCitationDto> evidence(
            JsonNode handles, Map<String, AiChatCitationDto> evidenceByHandle) {
        Set<String> uniqueHandles = new LinkedHashSet<>();
        List<AiChatCitationDto> projected = new ArrayList<>();
        for (JsonNode handle : handles) {
            if (!handle.isString() || !uniqueHandles.add(handle.asString())) {
                return null;
            }
            AiChatCitationDto citation = evidenceByHandle.get(handle.asString());
            if (citation == null) {
                return null;
            }
            projected.add(citation);
        }
        return List.copyOf(projected);
    }

    private static AiChatCoverageDto coverage(JsonNode value) {
        if (!exactFields(value, Set.of(
                "status", "asOf", "periodStart", "periodEnd",
                "sources", "exclusions", "truncated"))) {
            return null;
        }
        JsonNode status = value.get("status");
        JsonNode sources = value.get("sources");
        JsonNode exclusions = value.get("exclusions");
        JsonNode truncated = value.get("truncated");
        if (status == null || !status.isString()
                || !AiAssistantStepGuard.COVERAGE_STATUSES.contains(status.asString())
                || !nullableCoverageInstant(value.get("asOf"))
                || !nullableCoverageInstant(value.get("periodStart"))
                || !nullableCoverageInstant(value.get("periodEnd"))
                || !enumValues(sources, AiAssistantStepGuard.COVERAGE_SOURCES)
                || !enumValues(exclusions, AiAssistantStepGuard.COVERAGE_EXCLUSIONS)
                || truncated == null || !truncated.isBoolean()
                || ("complete".equals(status.asString())
                        && (truncated.asBoolean() || !exclusions.isEmpty()))) {
            return null;
        }
        return new AiChatCoverageDto(
                status.asString(), nullableValue(value.get("asOf")),
                nullableValue(value.get("periodStart")), nullableValue(value.get("periodEnd")),
                stringValues(sources), stringValues(exclusions), truncated.asBoolean());
    }

    private static List<AiChatProgressItemDto> progress(JsonNode values) {
        if (values == null || !values.isArray()
                || values.size() > AiAssistantStepGuard.MAX_BLOCKS) {
            return null;
        }
        Set<String> sources = new LinkedHashSet<>();
        List<AiChatProgressItemDto> progress = new ArrayList<>();
        Set<String> allowedSources = AiChatProgressService.PROGRESS_SOURCES;
        Set<String> allowedStatuses = AiChatProgressService.PROGRESS_STATUSES;
        for (JsonNode value : values) {
            if (!exactFields(value, Set.of("seq", "source", "status", "count", "truncated"))) {
                return null;
            }
            JsonNode seq = value.get("seq");
            JsonNode source = value.get("source");
            JsonNode status = value.get("status");
            JsonNode count = value.get("count");
            JsonNode truncated = value.get("truncated");
            if (seq == null || !seq.isIntegralNumber()
                    || !seq.canConvertToInt() || seq.asInt() < 0
                    || source == null || !source.isString()
                    || !allowedSources.contains(source.asString())
                    || !sources.add(source.asString())
                    || status == null || !status.isString()
                    || !allowedStatuses.contains(status.asString())
                    || count == null || (!count.isNull()
                            && (!count.isIntegralNumber() || !count.canConvertToInt()
                                    || count.asInt() < 0 || count.asInt() > 1_000))
                    || truncated == null || !truncated.isBoolean()) {
                return null;
            }
            progress.add(new AiChatProgressItemDto(
                    seq.asInt(), source.asString(), status.asString(),
                    count.isNull() ? null : count.asInt(), truncated.asBoolean()));
        }
        return List.copyOf(progress);
    }

    private static boolean exactFields(JsonNode value, Set<String> expected) {
        return value != null && value.isObject()
                && value.propertyNames().size() == expected.size()
                && value.propertyNames().containsAll(expected);
    }

    private static boolean nullableText(JsonNode value, int maxLength) {
        return value != null && (value.isNull() || text(value, maxLength));
    }

    /**
     * Revalidates a stored coverage timestamp on read. A legacy row written before the timestamp
     * was constrained can still hold model prose, so the projection fails closed on it here rather
     * than trusting that the step guard rejected it at generation time.
     */
    private static boolean nullableCoverageInstant(JsonNode value) {
        if (value == null) {
            return false;
        }
        return value.isNull()
                || (text(value, AiAssistantStepGuard.MAX_COVERAGE_INSTANT_CHARS)
                        && AiAssistantStepGuard.isCoverageInstant(value.asString()));
    }

    private static boolean text(JsonNode value, int maxLength) {
        return value != null && value.isString()
                && !value.asString().isBlank() && value.asString().length() <= maxLength;
    }

    private static String nullableValue(JsonNode value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean enumValues(JsonNode values, Set<String> allowed) {
        if (values == null || !values.isArray() || values.size() > allowed.size()) {
            return false;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isString() || !allowed.contains(value.asString())
                    || !unique.add(value.asString())) {
                return false;
            }
        }
        return true;
    }

    private static List<String> stringValues(JsonNode values) {
        List<String> projected = new ArrayList<>();
        for (JsonNode value : values) {
            projected.add(value.asString());
        }
        return List.copyOf(projected);
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

    private static List<AiChatProgressItemDto> sharedProgress(
            List<AiChatProgressItemDto> progress) {
        return progress.stream()
                .map(item -> new AiChatProgressItemDto(
                        item.seq(), item.source(), item.status(), null, false))
                .toList();
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

    private static AiChatCitationDto citation(StoredCitation citation, VisibleRecord record) {
        return new AiChatCitationDto(
                citation.handle(), citation.kind(), citation.id(),
                record.label(), record.asOf(), record.detail());
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

    private record StoredCitation(String handle, String kind, int id) {
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

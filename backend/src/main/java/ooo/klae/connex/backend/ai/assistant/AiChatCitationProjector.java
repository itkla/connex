package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.AiChatCitationDto;
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

    /** Returns authorized citations keyed by their containing message id. */
    public Map<Integer, List<AiChatCitationDto>> project(
            int workspaceId, List<AiChatMessage> messages) {
        Map<Integer, List<StoredCitation>> storedByMessage = new LinkedHashMap<>();
        Set<RecordKey> requested = new LinkedHashSet<>();
        for (AiChatMessage message : messages) {
            List<StoredCitation> stored = storedCitations(message);
            storedByMessage.put(message.getId(), stored);
            stored.stream()
                    .map(citation -> new RecordKey(citation.kind(), citation.id()))
                    .forEach(requested::add);
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
        return Map.copyOf(projected);
    }

    /** Returns validated demasked follow-up suggestions stored with one assistant message. */
    public List<String> suggestions(AiChatMessage message) {
        if (!"assistant".equals(message.getAuthorKind())
                || message.getStructuredJson() == null) {
            return List.of();
        }
        try {
            JsonNode suggestions = objectMapper.readTree(message.getStructuredJson())
                    .get("suggestions");
            if (suggestions == null || !suggestions.isArray()) {
                return List.of();
            }
            Set<String> projected = new LinkedHashSet<>();
            for (JsonNode suggestion : suggestions) {
                if (!suggestion.isString()) {
                    continue;
                }
                String value = suggestion.asString().strip();
                if (value.isBlank()
                        || value.length() > AiAssistantStepGuard.MAX_SUGGESTION_CHARS
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0
                        || AiAssistantStepGuard.containsHandle(value)
                        || AiAssistantStepGuard.containsControlInstruction(value)) {
                    continue;
                }
                projected.add(value);
                if (projected.size() == AiAssistantStepGuard.MAX_SUGGESTIONS) {
                    break;
                }
            }
            return List.copyOf(projected);
        } catch (JacksonException exception) {
            return List.of();
        }
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

    private record RecordKey(String kind, int id) {
    }
}

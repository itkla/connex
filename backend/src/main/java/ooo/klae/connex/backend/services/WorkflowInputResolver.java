package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkflowInputDefinition;
import ooo.klae.connex.backend.dto.WorkflowInputType;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowManualPreparationDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;

/** Resolves, validates, labels, and canonicalizes bounded schema-v2 launch inputs. */
@Service
@RequiredArgsConstructor
public class WorkflowInputResolver {

    private static final int MAX_CANONICAL_BYTES = 16 * 1024;
    private static final Pattern CANONICAL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public Resolved resolve(
            int workspaceId,
            List<WorkflowInputDefinition> definitions,
            Map<String, JsonNode> supplied) {
        List<WorkflowInputDefinition> declared = definitions == null ? List.of() : definitions;
        Map<String, JsonNode> values = supplied == null ? Map.of() : supplied;
        Set<String> known = declared.stream()
            .map(WorkflowInputDefinition::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String unknown = values.keySet().stream()
            .filter(key -> !known.contains(key))
            .sorted()
            .findFirst()
            .orElse(null);
        if (unknown != null) {
            throw invalid(
                WorkflowDiagnosticCode.INPUT_UNKNOWN,
                "Unknown workflow input: " + unknown,
                unknown);
        }
        Map<Integer, User> members = new LinkedHashMap<>();
        for (User member : workspaceService.getMembers(workspaceId)) {
            members.put(member.getId(), member);
        }
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        List<WorkflowManualPreparationDto.ResolvedInput> disclosure = new ArrayList<>();
        for (WorkflowInputDefinition definition : declared) {
            boolean suppliedValue = values.containsKey(definition.key());
            JsonNode value = suppliedValue ? values.get(definition.key()) : definition.defaultValue();
            String source = suppliedValue ? "supplied" : "default";
            if (value == null || value.isNull()) {
                if (definition.required()) {
                    throw invalid(
                        WorkflowDiagnosticCode.INPUT_REQUIRED,
                        "Required workflow input is missing: " + definition.key(),
                        definition.key());
                }
                if (suppliedValue) {
                    resolved.put(definition.key(), NullNode.getInstance());
                    disclosure.add(new WorkflowManualPreparationDto.ResolvedInput(
                        definition.key(), definition.label(), definition.type(), NullNode.getInstance(),
                        "", source));
                }
                continue;
            }
            if (definition.required()
                    && definition.type() == WorkflowInputType.TEXT
                    && value.isTextual()
                    && value.textValue().isBlank()) {
                throw invalid(
                    WorkflowDiagnosticCode.INPUT_REQUIRED,
                    "Required workflow input is blank: " + definition.key(),
                    definition.key());
            }
            requireType(definition, value, members);
            resolved.put(definition.key(), value);
            disclosure.add(new WorkflowManualPreparationDto.ResolvedInput(
                definition.key(),
                definition.label(),
                definition.type(),
                value,
                displayValue(definition.type(), value, members),
                source));
        }
        requireBounded(resolved);
        return new Resolved(
            Collections.unmodifiableMap(new LinkedHashMap<>(resolved)),
            List.copyOf(disclosure));
    }

    private static void requireType(
            WorkflowInputDefinition definition,
            JsonNode value,
            Map<Integer, User> members) {
        boolean valid = switch (definition.type()) {
            case TEXT -> value.isTextual() && value.textValue().length() <= 2000;
            case USER -> value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() > 0
                && members.containsKey(value.intValue());
            case DATE -> value.isTextual() && validDate(value.textValue());
        };
        if (!valid) {
            throw invalid(
                WorkflowDiagnosticCode.INPUT_TYPE_INVALID,
                "Workflow input has the wrong type: " + definition.key(),
                definition.key());
        }
    }

    private static String displayValue(
            WorkflowInputType type,
            JsonNode value,
            Map<Integer, User> members) {
        if (type != WorkflowInputType.USER) {
            return value.asString();
        }
        User member = members.get(value.intValue());
        if (member.getDisplayName() != null && !member.getDisplayName().isBlank()) {
            return member.getDisplayName().trim();
        }
        return member.getUsername() == null ? Integer.toString(member.getId()) : member.getUsername();
    }

    private void requireBounded(Map<String, JsonNode> values) {
        try {
            String json = objectMapper.writeValueAsString(values);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
                throw new BadRequestException("Workflow launch inputs are too large");
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Workflow launch inputs are malformed");
        }
    }

    private static boolean validDate(String value) {
        if (!CANONICAL_DATE.matcher(value).matches()) {
            return false;
        }
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static WorkflowDefinitionValidationException invalid(
            WorkflowDiagnosticCode code, String message, String key) {
        return new WorkflowDefinitionValidationException(
            message,
            new WorkflowDiagnosticDto(
                code,
                null,
                null,
                "inputs." + key,
                Map.of("key", key)));
    }

    /** Canonical launch values and safe UI disclosure derived from the same validation pass. */
    public record Resolved(
        Map<String, JsonNode> values,
        List<WorkflowManualPreparationDto.ResolvedInput> disclosure
    ) { }
}

package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Diff;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.DiffState;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pins the quick-create template contract without copying private default values into a card. */
final class AiAssistantCreationTemplatePin {
    private AiAssistantCreationTemplatePin() {
    }

    /** Resolves required inputs after the same defaults and intrinsic values as guided creation. */
    static Map<String, Object> prepare(
            RecordCreationPresetCatalogDto catalog, Set<String> supplied, Set<String> intrinsic) {
        ResolvedCreationTemplateDto selected = selected(catalog);
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (ResolvedCreationFieldDto field : selected.groups().stream()
                .flatMap(group -> group.fields().stream()).toList()) {
            boolean present = supplied.contains(field.key()) || intrinsic.contains(field.key());
            boolean defaulted = hasValue(field.defaultValue());
            if (field.required() && !present && !defaulted) {
                throw AiAssistantLoopException.refusedArguments("template_requires_fields");
            }
            if (!present && defaulted) {
                String key = field.customFieldId() == null ? field.key() : "customFields";
                defaults.put(key, "leadSource".equals(key) ? field.defaultValue().asString() : "");
            }
        }
        String name = selected.name().en();
        if (name == null || name.isBlank()) {
            name = selected.name().ja();
        }
        if (name == null || name.isBlank()) {
            throw new ResourceNotFoundException("Creation template is unavailable");
        }
        return Map.of(
                "templateId", selected.id(),
                "templateVersion", selected.version(),
                "templateSetRevision", catalog.setRevision(),
                "templateName", name,
                "defaultedFields", Map.copyOf(defaults));
    }

    /** Uses guided creation's first non-null core text default, including an empty string. */
    static List<String> identityDefault(RecordCreationPresetCatalogDto catalog, String key) {
        return selected(catalog).groups().stream()
                .flatMap(group -> group.fields().stream())
                .filter(field -> field.customFieldId() == null && key.equals(field.key()))
                .map(ResolvedCreationFieldDto::defaultValue)
                .filter(Objects::nonNull)
                .map(JsonNode::textValue)
                .filter(Objects::nonNull)
                .findFirst().map(List::of).orElseGet(List::of);
    }

    private static ResolvedCreationTemplateDto selected(RecordCreationPresetCatalogDto catalog) {
        return catalog.templates().stream()
                .filter(template -> template.id().equals(catalog.selectedTemplateId()))
                .filter(template -> template.availability() == RecordCreationTemplateAvailability.available)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Creation template is unavailable"));
    }

    /** Submits the proposal's exact template version; approval never selects a replacement. */
    static RecordCreationTemplateUseDto use(Map<String, Object> pinned, int companyId) {
        Object id = pinned.get("templateId");
        Object version = pinned.get("templateVersion");
        Object revision = pinned.get("templateSetRevision");
        if (!(id instanceof String templateId) || templateId.isBlank()
                || !(version instanceof Integer templateVersion) || templateVersion < 1
                || !(revision instanceof Integer templateSetRevision) || templateSetRevision < 0) {
            throw new ConflictException("Prepared creation template is unavailable");
        }
        return new RecordCreationTemplateUseDto(templateId, templateVersion, templateSetRevision,
                RecordCreationEntryPoint.quick_create, new RecordCreationContextDto(companyId));
    }

    /** Screens the pinned template label and exposes only field keys and the lead-source enum. */
    static List<Diff> diffs(Map<String, Object> pinned, ObjectMapper objectMapper) {
        Object name = pinned.get("templateName");
        Object defaults = pinned.get("defaultedFields");
        if (!(name instanceof String templateName) || templateName.isBlank()
                || SpecialCareTextScreen.screen(templateName).excluded()
                || !(defaults instanceof Map<?, ?> entries)) {
            return List.of(new Diff("template", null, false, null, DiffState.UNRESOLVED));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || !(entry.getValue() instanceof String value)
                    || SpecialCareTextScreen.screen(value).excluded()) {
                return List.of(new Diff("template", null, false, null, DiffState.UNRESOLVED));
            }
            values.put(key, value);
        }
        return List.of(
                new Diff("template", null, false, templateName, DiffState.CHANGED),
                new Diff("templateDefaults", null, false,
                        objectMapper.writeValueAsString(values), DiffState.CHANGED));
    }

    /** Mirrors guided creation's required-value semantics, including false and zero defaults. */
    private static boolean hasValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isString()) {
            return !value.asString().isBlank();
        }
        return !(value.isArray() || value.isObject()) || !value.isEmpty();
    }

    /** Keeps create inputs closed even when an old stored request bypasses catalog validation. */
    static void validateShape(String kind, JsonNode request, Set<String> fields) {
        if (!"company".equals(kind) || request == null || !request.isObject()) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        for (Map.Entry<String, JsonNode> entry : request.properties()) {
            if (!fields.contains(entry.getKey())
                    || (!entry.getValue().isNull() && !entry.getValue().isString())) {
                throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
            }
        }
        String handle = requiredText(request, "handle", 32);
        if (!handle.matches(AiAssistantWriteToolRequest.HANDLE)) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
    }

    /** Reads required bounded text without silently treating an invalid JSON type as empty. */
    static String requiredText(JsonNode request, String field, int maximum) {
        String value = optionalText(request, field, maximum);
        if (value == null) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        return value;
    }

    /** Null retains an absent optional input; blank text is never a create value. */
    static String optionalText(JsonNode request, String field, int maximum) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > maximum) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        return value.asString();
    }
}

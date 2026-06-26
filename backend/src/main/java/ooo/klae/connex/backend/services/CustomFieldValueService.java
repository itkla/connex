package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.CustomFieldValue;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;

/**
 * Reads and writes custom-field VALUES on a record. An internal collaborator of the
 * entity services (company/person/deal): not reachable from a controller directly,
 * so it carries no {@code @RequirePermission} — its callers gate writes with the
 * entity's update permission and assert the record is visible first. Definitions are
 * read straight from the mapper (not the admin-gated catalog service), so any member
 * who can see a record can render and fill its fields.
 */
@Service
@RequiredArgsConstructor
public class CustomFieldValueService {
    private final CustomFieldValueMapper valueMapper;
    private final CustomFieldDefinitionMapper definitionMapper;
    private final CustomFieldDefinitionService definitionService;
    private final WorkspaceService workspaceService;

    /**
     * Every non-archived field for the entity type, with this record's value (null if unset).
     */
    public List<CustomFieldEntryDto> getForEntity(String entityType, int entityId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return valueMapper.getForEntity(workspaceId, normalize(entityType), entityId).stream()
            .map(this::toEntry).toList();
    }

    /**
     * Replaces the record's custom-field values with the supplied set
     * ({@code definitionId → value}), coercing each by its field type and enforcing
     * required fields. Returns the resulting entries.
     */
    public List<CustomFieldEntryDto> applyValues(String entityType, int entityId, Map<Integer, Object> values) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String type = normalize(entityType);
        Map<Integer, CustomFieldDefinition> defs = definitionMapper.getByEntityType(workspaceId, type).stream()
            .filter(def -> !def.isArchived())
            .collect(Collectors.toMap(CustomFieldDefinition::getId, def -> def));
        Map<Integer, Object> input = values == null ? Map.of() : values;
        for (Integer definitionId : input.keySet()) {
            if (!defs.containsKey(definitionId)) {
                throw new BadRequestException("Unknown custom field: " + definitionId);
            }
        }
        List<CustomFieldValue> toUpsert = new ArrayList<>();
        List<Integer> toClear = new ArrayList<>();
        for (CustomFieldDefinition def : defs.values()) {
            CustomFieldValue value = coerce(workspaceId, type, entityId, def, input.get(def.getId()));
            if (value == null) {
                if (def.isRequired()) {
                    throw new BadRequestException("A value is required for '" + def.getLabel() + "'");
                }
                toClear.add(def.getId());
            } else {
                toUpsert.add(value);
            }
        }
        toClear.forEach(definitionId -> valueMapper.deleteByDefinitionAndEntity(workspaceId, definitionId, entityId));
        toUpsert.forEach(valueMapper::upsert);
        return valueMapper.getForEntity(workspaceId, type, entityId).stream().map(this::toEntry).toList();
    }

    /**
     * Removes all custom-field values for a record (called when the record is deleted).
     */
    public void deleteByEntity(String entityType, int entityId) {
        valueMapper.deleteByEntity(workspaceService.getCurrentWorkspaceId(), normalize(entityType), entityId);
    }

    private CustomFieldEntryDto toEntry(CustomFieldValue v) {
        CustomFieldEntryDto dto = new CustomFieldEntryDto();
        dto.setDefinitionId(v.getDefinitionId());
        dto.setFieldKey(v.getFieldKey());
        dto.setLabel(v.getLabel());
        dto.setFieldType(v.getFieldType());
        dto.setRequired(v.isRequired());
        dto.setOptions(definitionService.parseOptions(v.getOptionsJson()));
        dto.setValue(resolveValue(v));
        return dto;
    }

    private Object resolveValue(CustomFieldValue v) {
        return switch (v.getFieldType()) {
            case "number" -> v.getValueNumber();
            case "boolean" -> v.getValueBool();
            case "date" -> v.getValueDate() == null ? null
                : v.getValueDate().substring(0, Math.min(10, v.getValueDate().length()));
            default -> v.getValueText();
        };
    }

    private CustomFieldValue coerce(int workspaceId, String entityType, int entityId,
            CustomFieldDefinition def, Object raw) {
        String text = raw == null ? "" : raw.toString().trim();
        if (text.isEmpty()) return null;
        CustomFieldValue value = new CustomFieldValue();
        value.setWorkspaceId(workspaceId);
        value.setDefinitionId(def.getId());
        value.setEntityType(entityType);
        value.setEntityId(entityId);
        switch (def.getFieldType()) {
            case "text", "textarea" -> value.setValueText(text);
            case "url" -> {
                if (!text.matches("^https?://.+")) {
                    throw new BadRequestException("'" + def.getLabel() + "' must be a URL");
                }
                value.setValueText(text);
            }
            case "number" -> value.setValueNumber(parseNumber(def, text));
            case "date" -> value.setValueDate(parseDate(def, text));
            case "boolean" -> value.setValueBool(parseBool(def, text));
            case "select" -> {
                assertOption(def, text);
                value.setValueText(text);
            }
            default -> throw new BadRequestException("Unsupported field type: " + def.getFieldType());
        }
        return value;
    }

    private BigDecimal parseNumber(CustomFieldDefinition def, String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + def.getLabel() + "' must be a number");
        }
    }

    private String parseDate(CustomFieldDefinition def, String text) {
        try {
            return LocalDate.parse(text).toString();
        } catch (Exception e) {
            throw new BadRequestException("'" + def.getLabel() + "' must be a date (YYYY-MM-DD)");
        }
    }

    private Boolean parseBool(CustomFieldDefinition def, String text) {
        return switch (text.toLowerCase()) {
            case "true", "1", "yes" -> Boolean.TRUE;
            case "false", "0", "no" -> Boolean.FALSE;
            default -> throw new BadRequestException("'" + def.getLabel() + "' must be true or false");
        };
    }

    private void assertOption(CustomFieldDefinition def, String key) {
        List<CustomFieldOption> options = definitionService.parseOptions(def.getOptionsJson());
        boolean ok = options != null && options.stream().anyMatch(option -> key.equals(option.getKey()));
        if (!ok) {
            throw new BadRequestException("Invalid option for '" + def.getLabel() + "'");
        }
    }

    private static String normalize(String entityType) {
        return entityType == null ? null : entityType.trim().toLowerCase();
    }
}

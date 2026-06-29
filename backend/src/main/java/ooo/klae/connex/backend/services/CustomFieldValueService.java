package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 *
 * <p>Writes are partial: {@link #applyValues} and {@link #applyValue} only touch the
 * fields they are given, leaving the rest untouched. Clearing a required field is
 * rejected; a record is never blocked from saving one field by the state of another.
 */
@Service
@RequiredArgsConstructor
public class CustomFieldValueService {
    private final CustomFieldValueMapper valueMapper;
    private final CustomFieldDefinitionMapper definitionMapper;
    private final CustomFieldDefinitionService definitionService;
    private final WorkspaceService workspaceService;

    private static final int MAX_NUMERIC_DIGITS = 20;
    private static final int NUMERIC_SCALE = 4;

    /**
     * Every non-archived field for the entity type, with this record's value (null if unset).
     */
    public List<CustomFieldEntryDto> getForEntity(String entityType, int entityId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return valueMapper.getForEntity(workspaceId, normalize(entityType), entityId).stream()
            .map(this::toEntry).toList();
    }

    /**
     * Filled custom-field values for many records of one entity type, keyed by entity id
     * then definition id. Unset fields are simply absent. Used to populate table cells
     * without an N+1 of per-record reads.
     */
    public Map<Integer, Map<Integer, Object>> getForEntities(String entityType, List<Integer> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Map<Integer, Map<Integer, Object>> byEntity = new LinkedHashMap<>();
        for (CustomFieldValue value : valueMapper.getForEntities(workspaceId, normalize(entityType), entityIds)) {
            byEntity.computeIfAbsent(value.getEntityId(), id -> new LinkedHashMap<>())
                .put(value.getDefinitionId(), resolveValue(value));
        }
        return byEntity;
    }

    /**
     * Partial update: writes only the supplied fields ({@code definitionId → value}),
     * leaving the rest untouched. A blank value clears that field; clearing a required
     * field is rejected. Returns the record's resulting entries.
     */
    public List<CustomFieldEntryDto> applyValues(String entityType, int entityId, Map<Integer, Object> values) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String type = normalize(entityType);
        if (values != null && !values.isEmpty()) {
            Map<Integer, CustomFieldDefinition> defs = activeDefs(workspaceId, type);
            for (Map.Entry<Integer, Object> entry : values.entrySet()) {
                writeOne(workspaceId, type, entityId, requireDef(defs, entry.getKey()), entry.getValue());
            }
        }
        return valueMapper.getForEntity(workspaceId, type, entityId).stream().map(this::toEntry).toList();
    }

    /**
     * Sets or clears a single custom-field value on a record. A blank value clears it;
     * clearing a required field is rejected. Returns the record's resulting entries.
     */
    public List<CustomFieldEntryDto> applyValue(String entityType, int entityId, int definitionId, Object raw) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String type = normalize(entityType);
        writeOne(workspaceId, type, entityId, requireDef(activeDefs(workspaceId, type), definitionId), raw);
        return valueMapper.getForEntity(workspaceId, type, entityId).stream().map(this::toEntry).toList();
    }

    /**
     * Removes all custom-field values for a record (called when the record is deleted).
     */
    public void deleteByEntity(String entityType, int entityId) {
        valueMapper.deleteByEntity(workspaceService.getCurrentWorkspaceId(), normalize(entityType), entityId);
    }

    /**
     * Validates a raw value against a field definition exactly as a write would (running the same
     * coercion), throwing {@code BadRequestException} on an invalid value without persisting anything.
     * Used by the CSV-import preview so it matches commit-time validation.
     */
    public void validateValue(CustomFieldDefinition def, Object raw) {
        coerce(workspaceService.getCurrentWorkspaceId(), def.getEntityType(), 0, def, raw);
    }

    private void writeOne(int workspaceId, String entityType, int entityId, CustomFieldDefinition def, Object raw) {
        CustomFieldValue value = coerce(workspaceId, entityType, entityId, def, raw);
        if (value == null) {
            if (def.isRequired()) {
                throw new BadRequestException("A value is required for '" + def.getLabel() + "'");
            }
            valueMapper.deleteByDefinitionAndEntity(workspaceId, def.getId(), entityId);
        } else {
            valueMapper.upsert(value);
        }
    }

    private Map<Integer, CustomFieldDefinition> activeDefs(int workspaceId, String entityType) {
        return definitionMapper.getByEntityType(workspaceId, entityType).stream()
            .filter(def -> !def.isArchived())
            .collect(Collectors.toMap(CustomFieldDefinition::getId, def -> def));
    }

    private CustomFieldDefinition requireDef(Map<Integer, CustomFieldDefinition> defs, Integer definitionId) {
        CustomFieldDefinition def = defs.get(definitionId);
        if (def == null) {
            throw new BadRequestException("Unknown custom field: " + definitionId);
        }
        return def;
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
        if (text.isEmpty()) {
            return null;
        }
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
        BigDecimal number;
        try {
            number = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + def.getLabel() + "' must be a number");
        }
        if (number.scale() > MAX_NUMERIC_DIGITS
            || number.precision() - number.scale() > MAX_NUMERIC_DIGITS - NUMERIC_SCALE) {
            throw new BadRequestException("'" + def.getLabel() + "' is out of range");
        }
        return number.setScale(NUMERIC_SCALE, RoundingMode.HALF_UP);
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

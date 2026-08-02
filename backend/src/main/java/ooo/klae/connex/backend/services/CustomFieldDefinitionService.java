package ooo.klae.connex.backend.services;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Business logic for the custom-field catalog — which fields a workspace's records may carry.
 * Defining, editing, and retiring a field is administration and requires
 * {@code CUSTOM_FIELD_MANAGE}; reading the catalog is not, because the definitions describe the
 * shape of records the caller can already read. Any member of the active workspace may read
 * them, exactly as {@code CustomFieldValueService} already lets any member who can see a record
 * read that record's definitions. Every read stays workspace-scoped through the mapper.
 * {@code entityType}, {@code fieldKey}, and {@code fieldType} are fixed at
 * creation; label, data classification, options, required, position, and
 * archived are editable.
 * Delegates persistence to {@code CustomFieldDefinitionMapper}.
 */
@Service
@RequiredArgsConstructor
public class CustomFieldDefinitionService {
    private static final Logger log = LoggerFactory.getLogger(CustomFieldDefinitionService.class);

    private final CustomFieldDefinitionMapper definitionMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    private static final Set<String> ENTITY_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> FIELD_TYPES =
        Set.of("text", "textarea", "number", "date", "boolean", "select", "url");
    private static final Set<String> DATA_CLASSIFICATIONS = Set.of("standard", "sensitive", "special_care");
    private static final String DEFAULT_CLASSIFICATION = "standard";
    private static final Set<String> AUDIT_FIELDS =
        Set.of("entityType", "fieldKey", "label", "fieldType", "dataClassification", "optionsJson",
            "required", "position", "archived");

    /**
     * All field definitions in the active workspace, across entity types. Readable by any member
     * of the workspace so list views can render the columns their records carry.
     */
    public List<CustomFieldDefinition> getAll() {
        return definitionMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Field definitions for one entity type in the active workspace. Readable by any member of
     * the workspace so list views can render the columns their records carry.
     */
    public List<CustomFieldDefinition> getByEntityType(String entityType) {
        return definitionMapper.getByEntityType(workspaceService.getCurrentWorkspaceId(), normalize(entityType));
    }

    /**
     * A single definition by ID, scoped to the active workspace. Readable by any member of the
     * workspace.
     */
    public CustomFieldDefinition getById(int id) {
        CustomFieldDefinition def = definitionMapper.getById(workspaceService.getCurrentWorkspaceId(), id);
        if (def == null) throw new ResourceNotFoundException("Custom field not found with id: " + id);
        return def;
    }

    /**
     * Defines a new custom field in the active workspace.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public CustomFieldDefinition create(CustomFieldDefinition def, List<CustomFieldOption> options) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        def.setWorkspaceId(workspaceId);
        def.setEntityType(normalize(def.getEntityType()));
        validateShape(def, options);
        def.setOptionsJson(serializeOptions(def.getFieldType(), options));
        assertUniqueKey(workspaceId, def.getEntityType(), def.getFieldKey());
        definitionMapper.insert(def);
        auditService.record("custom_field.create", "custom_field", def.getId(), def.getLabel(),
            "Created custom field " + def.getLabel(),
            auditService.diff(null, def, AUDIT_FIELDS));
        return def;
    }

    /**
     * Updates a field's editable attributes. {@code entityType}, {@code fieldKey},
     * and {@code fieldType} are immutable — preserved from the stored record so a
     * structural change can never orphan existing values. {@code dataClassification}
     * is editable, but an omitted (null/blank) value preserves the stored one rather
     * than resetting it, so a partial update can never silently downgrade a
     * special-care marking.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public CustomFieldDefinition update(int id, CustomFieldDefinition def, List<CustomFieldOption> options) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        CustomFieldDefinition before = definitionMapper.getById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Custom field not found with id: " + id);
        def.setId(id);
        def.setWorkspaceId(workspaceId);
        def.setEntityType(before.getEntityType());
        def.setFieldKey(before.getFieldKey());
        def.setFieldType(before.getFieldType());
        if (def.getDataClassification() == null || def.getDataClassification().isBlank()) {
            def.setDataClassification(before.getDataClassification());
        }
        validateShape(def, options);
        def.setOptionsJson(serializeOptions(def.getFieldType(), options));
        definitionMapper.update(def);
        auditService.record("custom_field.update", "custom_field", id, def.getLabel(),
            "Updated custom field " + def.getLabel(),
            auditService.diff(before, def, AUDIT_FIELDS));
        return def;
    }

    /**
     * Permanently deletes a field and (via cascade) its values.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        CustomFieldDefinition before = definitionMapper.getById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Custom field not found with id: " + id);
        definitionMapper.delete(workspaceId, id);
        auditService.record("custom_field.delete", "custom_field", id, before.getLabel(),
            "Deleted custom field " + before.getLabel(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /**
     * Parses the stored {@code options_json} into typed options for the API; null
     * when a field has no options. Malformed JSON is logged and treated as none.
     */
    public List<CustomFieldOption> parseOptions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return List.of(objectMapper.readValue(json, CustomFieldOption[].class));
        } catch (Exception e) {
            log.warn("Failed to parse custom-field options JSON", e);
            return null;
        }
    }

    /**
     * Validates label, entity type, field type, and the select-option invariants.
     */
    private void validateShape(CustomFieldDefinition def, List<CustomFieldOption> options) {
        if (def.getLabel() == null || def.getLabel().isBlank()) {
            throw new BadRequestException("A field label is required");
        }
        if (!ENTITY_TYPES.contains(def.getEntityType())) {
            throw new BadRequestException("Unsupported entity type: " + def.getEntityType());
        }
        if (!FIELD_TYPES.contains(def.getFieldType())) {
            throw new BadRequestException("Unsupported field type: " + def.getFieldType());
        }
        if (def.getDataClassification() == null || def.getDataClassification().isBlank()) {
            def.setDataClassification(DEFAULT_CLASSIFICATION);
        } else if (!DATA_CLASSIFICATIONS.contains(def.getDataClassification())) {
            throw new BadRequestException("Unsupported data classification: " + def.getDataClassification());
        }
        if ("select".equals(def.getFieldType())) {
            if (options == null || options.isEmpty()) {
                throw new BadRequestException("A select field requires at least one option");
            }
            Set<String> keys = new HashSet<>();
            for (CustomFieldOption option : options) {
                if (option.getKey() == null || option.getKey().isBlank()) {
                    throw new BadRequestException("Select options require a key");
                }
                if (option.getLabel() == null || option.getLabel().isBlank()) {
                    throw new BadRequestException("Select options require a label");
                }
                if (!keys.add(option.getKey())) {
                    throw new BadRequestException("Duplicate option key: " + option.getKey());
                }
            }
        } else if (options != null && !options.isEmpty()) {
            throw new BadRequestException("Only select fields may declare options");
        }
    }

    /**
     * Serializes select options to JSON; null for non-select fields.
     */
    private String serializeOptions(String fieldType, List<CustomFieldOption> options) {
        if (!"select".equals(fieldType) || options == null || options.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            throw new BadRequestException("Invalid select options");
        }
    }

    /**
     * Rejects a duplicate (entity type, field key) within the workspace. The unique
     * index is the authoritative guard; this returns a field-specific 409.
     */
    private void assertUniqueKey(int workspaceId, String entityType, String fieldKey) {
        if (definitionMapper.getByKey(workspaceId, entityType, fieldKey) != null) {
            throw new DuplicateResourceException("fieldKey",
                "A " + entityType + " field with key '" + fieldKey + "' already exists");
        }
    }

    private static String normalize(String entityType) {
        return entityType == null ? null : entityType.trim().toLowerCase();
    }
}

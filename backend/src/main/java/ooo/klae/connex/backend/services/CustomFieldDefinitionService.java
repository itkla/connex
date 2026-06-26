package ooo.klae.connex.backend.services;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Business logic for the custom-field catalog. Defining fields is an admin act
 * ({@code CUSTOM_FIELD_MANAGE}); reads are membership-gated so any member can
 * render a record's fields. {@code entityType} and {@code fieldKey} are fixed at
 * creation; everything else is editable. Delegates persistence to
 * {@code CustomFieldDefinitionMapper}.
 */
@Service
@RequiredArgsConstructor
public class CustomFieldDefinitionService {
    private final CustomFieldDefinitionMapper definitionMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ENTITY_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> FIELD_TYPES =
        Set.of("text", "textarea", "number", "date", "boolean", "select", "url");
    private static final Set<String> AUDIT_FIELDS =
        Set.of("entityType", "fieldKey", "label", "fieldType", "optionsJson", "required", "position", "archived");

    /**
     * All field definitions in the active workspace, across entity types.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public List<CustomFieldDefinition> getAll() {
        return definitionMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Field definitions for one entity type in the active workspace.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
    public List<CustomFieldDefinition> getByEntityType(String entityType) {
        return definitionMapper.getByEntityType(workspaceService.getCurrentWorkspaceId(), normalize(entityType));
    }

    /**
     * A single definition by ID, scoped to the active workspace.
     */
    @RequirePermission(Permission.CUSTOM_FIELD_MANAGE)
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
        assertUniqueKey(workspaceId, def.getEntityType(), def.getFieldKey(), 0);
        definitionMapper.insert(def);
        auditService.record("custom_field.create", "custom_field", def.getId(), def.getLabel(),
            "Created custom field " + def.getLabel(),
            auditService.diff(null, def, AUDIT_FIELDS));
        return def;
    }

    /**
     * Updates an existing field's editable attributes. {@code entityType} and
     * {@code fieldKey} are preserved from the stored record.
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
     * Validates entity type, field type, and the select-option invariants.
     */
    private void validateShape(CustomFieldDefinition def, List<CustomFieldOption> options) {
        if (!ENTITY_TYPES.contains(def.getEntityType())) {
            throw new BadRequestException("Unsupported entity type: " + def.getEntityType());
        }
        if (!FIELD_TYPES.contains(def.getFieldType())) {
            throw new BadRequestException("Unsupported field type: " + def.getFieldType());
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
                if (!keys.add(option.getKey())) {
                    throw new BadRequestException("Duplicate option key: " + option.getKey());
                }
            }
        } else if (options != null && !options.isEmpty()) {
            throw new BadRequestException("Only select fields may declare options");
        }
    }

    /**
     * Serializes select options to JSON; returns null for non-select fields.
     */
    private String serializeOptions(String fieldType, List<CustomFieldOption> options) {
        if (!"select".equals(fieldType) || options == null || options.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Invalid select options");
        }
    }

    /**
     * Rejects a duplicate (entity type, field key) within the workspace.
     */
    private void assertUniqueKey(int workspaceId, String entityType, String fieldKey, int selfId) {
        CustomFieldDefinition existing = definitionMapper.getByKey(workspaceId, entityType, fieldKey);
        if (existing != null && existing.getId() != selfId) {
            throw new DuplicateResourceException("fieldKey",
                "A " + entityType + " field with key '" + fieldKey + "' already exists");
        }
    }

    private static String normalize(String entityType) {
        return entityType == null ? null : entityType.trim().toLowerCase();
    }
}

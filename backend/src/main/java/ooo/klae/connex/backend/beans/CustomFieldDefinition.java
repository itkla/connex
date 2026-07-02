package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-defined custom field for a given entity type ({@code company},
 * {@code person}, or {@code deal}) — the catalog of extra columns a workspace's
 * records may carry. Values are stored separately in {@code custom_field_value}.
 * {@code optionsJson} holds the JSON choice list for {@code select} fields.
 * Mapped via {@code CustomFieldDefinitionMapper} / {@code CustomFieldDefinitionMapper.xml}.
 */
@Data
@NoArgsConstructor
public class CustomFieldDefinition {
    private int id;
    private int workspaceId;
    private String entityType;
    private String fieldKey;
    private String label;
    private String fieldType;
    private String dataClassification;
    private String optionsJson;
    private boolean required;
    private int position;
    private boolean archived;
    private String createdAt;
    private String updatedAt;
}

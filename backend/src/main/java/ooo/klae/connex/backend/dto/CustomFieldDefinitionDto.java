package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;

/**
 * API representation of a {@link CustomFieldDefinition}. {@code options} is a typed
 * list on the wire; the service serializes it to / parses it from the stored
 * {@code options_json}, so the DTO itself never touches JSON. {@code entityType},
 * {@code fieldKey}, and {@code fieldType} are immutable once a field is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomFieldDefinitionDto {

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer id;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer workspaceId;

    @NotBlank
    @Pattern(regexp = "^(company|person|deal)$", message = "must be company, person, or deal")
    private String entityType;

    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$", message = "must be lowercase letters, digits, and underscores")
    private String fieldKey;

    @NotBlank
    @Size(max = 128)
    private String label;

    @NotBlank
    @Pattern(regexp = "^(text|textarea|number|date|boolean|select|url)$", message = "Choose a supported field type.")
    private String fieldType;

    @Pattern(
        regexp = "^(standard|sensitive|special_care)$",
        message = "Choose how sensitive this field's data is.")
    private String dataClassification;

    @Valid
    private List<CustomFieldOption> options;

    private Boolean required;

    @PositiveOrZero
    private Integer position;

    private Boolean archived;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String updatedAt;

    public static CustomFieldDefinitionDto from(CustomFieldDefinition d) {
        if (d == null) return null;
        CustomFieldDefinitionDto dto = new CustomFieldDefinitionDto();
        dto.id = d.getId();
        dto.workspaceId = d.getWorkspaceId();
        dto.entityType = d.getEntityType();
        dto.fieldKey = d.getFieldKey();
        dto.label = d.getLabel();
        dto.fieldType = d.getFieldType();
        dto.dataClassification = d.getDataClassification();
        dto.required = d.isRequired();
        dto.position = d.getPosition();
        dto.archived = d.isArchived();
        dto.createdAt = d.getCreatedAt();
        dto.updatedAt = d.getUpdatedAt();
        return dto;
    }

    public CustomFieldDefinition toBean() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        if (id != null) d.setId(id);
        d.setEntityType(entityType);
        d.setFieldKey(fieldKey);
        d.setLabel(label);
        d.setFieldType(fieldType);
        d.setDataClassification(dataClassification);
        d.setRequired(required != null && required);
        d.setPosition(position != null ? position : 0);
        d.setArchived(archived != null && archived);
        return d;
    }
}

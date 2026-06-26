package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;

/**
 * API representation of a {@link CustomFieldDefinition}. {@code options} is
 * exposed as a typed list and persisted as JSON in the bean's {@code optionsJson};
 * {@code entityType} and {@code fieldKey} are immutable once a field is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomFieldDefinitionDto {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    @Pattern(regexp = "^(text|textarea|number|date|boolean|select|url)$", message = "unsupported field type")
    private String fieldType;

    @Valid
    private List<CustomFieldOption> options;

    private boolean required;

    private int position;

    private boolean archived;

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
        dto.options = parseOptions(d.getOptionsJson());
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
        d.setRequired(required);
        d.setPosition(position);
        d.setArchived(archived);
        return d;
    }

    private static List<CustomFieldOption> parseOptions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                .constructCollectionType(List.class, CustomFieldOption.class));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}

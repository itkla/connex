package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A custom-field value on a record. The value lives in exactly one typed column
 * chosen by the definition's field type. On reads ({@code getForEntity}) the row
 * is the definition LEFT-JOINed with the value, so the definition fields
 * ({@code definitionId}, {@code fieldKey}, {@code label}, {@code fieldType},
 * {@code optionsJson}, {@code required}, {@code position}) are always present while
 * the value columns are null for an unfilled field. Mapped via {@code CustomFieldValueMapper}.
 */
@Data
@NoArgsConstructor
public class CustomFieldValue {
    private int id;
    private int workspaceId;
    private int definitionId;
    private String entityType;
    private int entityId;
    private String valueText;
    private BigDecimal valueNumber;
    private String valueDate;
    private Boolean valueBool;
    private String createdAt;
    private String updatedAt;
    private String fieldKey;
    private String label;
    private String fieldType;
    private String optionsJson;
    private boolean required;
    private int position;
}

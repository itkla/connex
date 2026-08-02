package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * The member-facing projection of a custom-field definition: only what is needed to render and
 * edit a field in a list view.
 *
 * This deliberately carries less than {@link CustomFieldDefinitionDto}. Reading the admin catalog
 * stays gated on {@code CUSTOM_FIELD_MANAGE}, so the fields omitted here are omitted on purpose:
 *
 * <ul>
 *   <li>{@code dataClassification} — {@code special_care} marks 要配慮個人情報 under APPI, so the
 *       catalog doubles as a map of which fields hold special-care personal data. That map is
 *       administrative knowledge and is not needed to draw a column.</li>
 *   <li>{@code archived} definitions are excluded entirely, matching the long-standing member path
 *       for a single record. Retired schema is where withdrawn or sensitive field names survive.</li>
 *   <li>{@code fieldKey}, {@code position}, {@code workspaceId}, {@code entityType},
 *       {@code createdAt}, {@code updatedAt} — internal or already implied by the request.</li>
 * </ul>
 *
 * @param definitionId the definition this column renders, named as in {@link CustomFieldEntryDto}
 * @param label the column heading
 * @param fieldType one of the supported editor types
 * @param options the select options, or null for every other field type
 * @param required whether the editor must refuse an empty value
 */
public record CustomFieldSchemaDto(
    int definitionId,
    String label,
    String fieldType,
    List<CustomFieldOption> options,
    boolean required
) {
}

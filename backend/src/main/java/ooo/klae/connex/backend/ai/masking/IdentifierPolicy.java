package ooo.klae.connex.backend.ai.masking;

import java.util.Set;

/**
 * Structural AI masking policy for core CRM fields that do not carry custom-field
 * {@code dataClassification} metadata. The table is deliberately fail-closed: unknown field kinds
 * and missing classifications are excluded, special-care values are always excluded, and sensitive
 * values are excluded unless a caller explicitly opts in. Amounts and dates are allowed by default
 * as non-direct identifiers, but they remain potentially identifying in aggregate and should be
 * minimized by feature assemblers.
 */
public final class IdentifierPolicy {

    private static final Set<FieldKind> TOKENIZED_FIELDS = Set.of(
            FieldKind.PERSON_FULL_NAME,
            FieldKind.COMPANY_NAME);
    private static final Set<FieldKind> EXCLUDED_FIELDS = Set.of(
            FieldKind.EMAIL,
            FieldKind.PHONE,
            FieldKind.ADDRESS,
            FieldKind.WEBSITE,
            FieldKind.IMAGE_URL,
            FieldKind.LOGO_URL);
    private static final Set<FieldKind> ALLOWED_FIELDS = Set.of(
            FieldKind.WARMTH_BAND,
            FieldKind.TREND,
            FieldKind.DAYS_SINCE_TOUCH,
            FieldKind.RISK_FACTOR_CODE,
            FieldKind.DEAL_STAGE_NAME,
            FieldKind.AMOUNT,
            FieldKind.DATE,
            FieldKind.TITLE,
            FieldKind.ROLE);

    private IdentifierPolicy() {
    }

    /**
     * Provider exposure mode for a known structural CRM field.
     */
    public enum MaskMode {
        TOKENIZE,
        EXCLUDE,
        ALLOW
    }

    /**
     * Feature-agnostic field categories used by prompt assemblers.
     */
    public enum FieldKind {
        PERSON_FULL_NAME,
        COMPANY_NAME,
        EMAIL,
        PHONE,
        ADDRESS,
        WEBSITE,
        IMAGE_URL,
        LOGO_URL,
        WARMTH_BAND,
        TREND,
        DAYS_SINCE_TOUCH,
        RISK_FACTOR_CODE,
        DEAL_STAGE_NAME,
        AMOUNT,
        DATE,
        TITLE,
        ROLE
    }

    /**
     * Returns the default masking mode for a structural field.
     * @param fieldKind field category known to the prompt assembler
     * @return the field mode, failing closed to {@link MaskMode#EXCLUDE} when absent or unknown
     */
    public static MaskMode fieldMode(FieldKind fieldKind) {
        if (fieldKind == null) {
            return MaskMode.EXCLUDE;
        }
        if (TOKENIZED_FIELDS.contains(fieldKind)) {
            return MaskMode.TOKENIZE;
        }
        if (ALLOWED_FIELDS.contains(fieldKind)) {
            return MaskMode.ALLOW;
        }
        if (EXCLUDED_FIELDS.contains(fieldKind)) {
            return MaskMode.EXCLUDE;
        }
        return MaskMode.EXCLUDE;
    }

    /**
     * Returns the default masking mode for a classified custom field.
     * @param classification custom-field data classification
     * @return the classification mode with sensitive values excluded
     */
    public static MaskMode classificationMode(DataClassification classification) {
        return classificationMode(classification, false);
    }

    /**
     * Returns the masking mode for a classified custom field.
     * @param classification custom-field data classification
     * @param includeSensitive whether the caller has explicitly opted into sending sensitive values
     * @return the classification mode, with special-care data always excluded
     */
    public static MaskMode classificationMode(DataClassification classification, boolean includeSensitive) {
        if (classification == null) {
            return MaskMode.EXCLUDE;
        }
        return switch (classification) {
            case SPECIAL_CARE -> MaskMode.EXCLUDE;
            case SENSITIVE -> includeSensitive ? MaskMode.ALLOW : MaskMode.EXCLUDE;
            case STANDARD -> MaskMode.ALLOW;
        };
    }
}

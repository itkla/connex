package ooo.klae.connex.backend.dto;

/**
 * One ranked source row backing a relationship score, bounded by the server's contributor limit.
 */
public record RelationshipEvidenceRowDto(
    String sourceType,
    int sourceId,
    String interactionType,
    String occurredAt,
    double baseWeight,
    double decayedContribution,
    String providerName,
    String providerStream,
    String providerSourceId,
    String providerCapturedAt,
    String captureAsOf,
    String providerVisibility,
    String providerAdmittedFieldsJson,
    String providerMaterialExclusionsJson
) {
    /** Compatibility constructor for evidence rows without provider provenance. */
    public RelationshipEvidenceRowDto(
            String sourceType,
            int sourceId,
            String interactionType,
            String occurredAt,
            double baseWeight,
            double decayedContribution) {
        this(
            sourceType,
            sourceId,
            interactionType,
            occurredAt,
            baseWeight,
            decayedContribution,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    /** Returns immutable provider provenance for a captured activity contributor. */
    public CaptureEvidenceDto captureEvidence() {
        return CaptureEvidenceDto.from(
            providerName,
            providerStream,
            providerSourceId,
            providerCapturedAt,
            captureAsOf,
            providerVisibility,
            providerAdmittedFieldsJson,
            providerMaterialExclusionsJson);
    }
}

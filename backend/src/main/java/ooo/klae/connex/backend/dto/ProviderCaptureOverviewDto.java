package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Complete provider-neutral connected-capture settings and health projection.
 */
public record ProviderCaptureOverviewDto(
    String provider,
    CapturePolicy userPolicy,
    WorkspacePolicy workspacePolicy,
    EffectivePolicy effectivePolicy,
    List<StreamState> streams,
    long reviewCount,
    long pendingApprovalCount,
    boolean activationReady,
    boolean retainedData,
    boolean accountResetAvailable,
    Disclosures disclosures,
    PurgeState purge
) {
    /** Defensively copies stream state. */
    public ProviderCaptureOverviewDto {
        streams = List.copyOf(streams);
    }

    /** User policy view. */
    public record CapturePolicy(
        boolean enabled,
        boolean calendar,
        boolean mailInbox,
        boolean mailSent,
        int backfillDays,
        boolean includeBodies,
        String admissionMode,
        boolean reviewBeforeCapture,
        List<String> excludedPeople,
        List<String> excludedConversations,
        long version,
        String updatedAt
    ) {
        /** Defensively copies explicit user exclusions. */
        public CapturePolicy {
            excludedPeople = List.copyOf(excludedPeople);
            excludedConversations = List.copyOf(excludedConversations);
        }
    }

    /** Workspace policy ceiling. */
    public record WorkspacePolicy(
        boolean allowed,
        boolean calendar,
        boolean mailInbox,
        boolean mailSent,
        int maxBackfillDays,
        boolean bodyCaptureAllowed,
        boolean reviewRequired,
        boolean excludePrivateEvents,
        boolean excludeInternalOnly,
        List<String> excludedDomains,
        long version,
        String updatedAt
    ) {
        /** Defensively copies excluded domains. */
        public WorkspacePolicy {
            excludedDomains = List.copyOf(excludedDomains);
        }
    }

    /** Effective restrictive intersection plus stable explanation codes. */
    public record EffectivePolicy(
        boolean enabled,
        boolean calendar,
        boolean mailInbox,
        boolean mailSent,
        int backfillDays,
        boolean includeBodies,
        String admissionMode,
        List<String> restrictionCodes
    ) {
        /** Defensively copies restriction codes. */
        public EffectivePolicy {
            restrictionCodes = List.copyOf(restrictionCodes);
        }
    }

    /** One stream's durable status and progress. */
    public record StreamState(
        String stream,
        String status,
        long processedItems,
        Long estimatedItems,
        String lastAttemptAt,
        String lastSuccessAt,
        String nextAttemptAt,
        String errorCode
    ) {
    }

    /** Stable localization codes describing capture semantics. */
    public record Disclosures(
        List<String> scopes,
        List<String> admittedFields,
        List<String> materialExclusions,
        List<String> visibility,
        List<String> retention
    ) {
        /** Defensively copies disclosure code lists. */
        public Disclosures {
            scopes = List.copyOf(scopes);
            admittedFields = List.copyOf(admittedFields);
            materialExclusions = List.copyOf(materialExclusions);
            visibility = List.copyOf(visibility);
            retention = List.copyOf(retention);
        }
    }

    /** Provider data purge lifecycle. */
    public record PurgeState(boolean active, String status, String errorCode) {
    }
}

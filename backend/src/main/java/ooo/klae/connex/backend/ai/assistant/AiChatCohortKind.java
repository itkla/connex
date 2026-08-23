package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single rule that decides which record kind and which warmth bands one declared scope reads.
 *
 * <p>The scope preview and the executed retrieval both resolve through here, so the breadth a
 * member confirms describes the same set the turn goes on to read, and a request the retrieval
 * would refuse is refused before it is confirmed rather than after it has already run.
 *
 * <p>A model argument may only narrow. Where the caller declared a facet, an argument outside that
 * declaration is refused rather than substituted: a substituted facet would leave the scope chip
 * the requester was shown describing a query the server never performed.
 */
final class AiChatCohortKind {

    /** Stable refusal reason for a warmth filter asked of a deal cohort. */
    static final String WARMTH_UNSUPPORTED_FOR_DEALS = "warmth_unsupported_for_deals";

    /** Stable refusal reason for a record kind outside the caller's declared kinds. */
    static final String RECORD_KIND_OUTSIDE_SCOPE = "record_kind_outside_declared_scope";

    /** Stable refusal reason for a warmth argument disjoint from the caller's declared bands. */
    static final String WARMTH_OUTSIDE_SCOPE = "warmth_outside_declared_scope";

    /** Stable refusal reason for stage or status filters asked of a non-deal cohort. */
    static final String STAGE_SCOPE_UNSUPPORTED = "stage_scope_unsupported_for_cohort";

    /** Stable refusal reason for a deal-attention review the declared statuses exclude. */
    static final String DEAL_STATUS_UNSUPPORTED_FOR_ATTENTION =
            "deal_status_unsupported_for_attention";

    /** Stable refusal reason for a declaration of several kinds that nothing narrows to one. */
    static final String RECORD_KIND_AMBIGUOUS = "record_kind_ambiguous_for_cohort";

    /** Record kinds a cohort may cover. */
    static final Set<String> KINDS = Set.of("person", "company", "deal");

    private static final Set<String> BANDS = Set.of("hot", "warm", "cool", "cold");
    private static final String DEAL = "deal";

    private AiChatCohortKind() {
    }

    /**
     * The cohort facets one turn will actually read.
     *
     * @param kind resolved cohort record kind
     * @param bands resolved warmth bands, empty when the cohort is unfiltered by warmth
     */
    record Cohort(String kind, List<String> bands) {
        Cohort {
            bands = List.copyOf(bands);
        }
    }

    /**
     * Resolves the cohort facets, refusing anything that would widen or silently drop a declaration.
     *
     * @param scope validated declared turn scope
     * @param requestedKind record kind proposed as a model argument, or null
     * @param contextKind record kind derived from the page the turn is anchored to, or null
     * @param requestedBands warmth bands proposed as a model argument, or empty
     * @return the record kind and warmth bands the retrieval will use
     */
    static Cohort resolve(
            AiChatQueryScope scope,
            String requestedKind,
            String contextKind,
            List<String> requestedBands) {
        List<String> bands = bands(scope, requestedBands);
        String kind = kind(scope, requestedKind, contextKind);
        if (DEAL.equals(kind) && !bands.isEmpty()) {
            throw AiAssistantLoopException.malformed(WARMTH_UNSUPPORTED_FOR_DEALS);
        }
        if (!DEAL.equals(kind)
                && (!scope.stageIds().isEmpty() || !scope.dealStatuses().isEmpty())) {
            throw AiAssistantLoopException.malformed(STAGE_SCOPE_UNSUPPORTED);
        }
        return new Cohort(kind, bands);
    }

    private static List<String> bands(AiChatQueryScope scope, List<String> requestedBands) {
        List<String> declared = scope.warmthBands();
        if (requestedBands == null || requestedBands.isEmpty()) {
            return declared;
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String value : requestedBands) {
            String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!BANDS.contains(candidate)) {
                throw AiAssistantLoopException.malformed("invalid_tool_arguments");
            }
            requested.add(candidate);
        }
        if (declared.isEmpty()) {
            return List.copyOf(requested);
        }
        List<String> narrowed = declared.stream().filter(requested::contains).toList();
        if (narrowed.isEmpty()) {
            throw AiAssistantLoopException.malformed(WARMTH_OUTSIDE_SCOPE);
        }
        return narrowed;
    }

    private static String kind(
            AiChatQueryScope scope,
            String requestedKind,
            String contextKind) {
        List<String> declared = scope.recordKinds();
        if (requestedKind != null && !requestedKind.isBlank()) {
            String candidate = requestedKind.trim().toLowerCase(Locale.ROOT);
            if (!KINDS.contains(candidate)) {
                throw AiAssistantLoopException.malformed("invalid_tool_arguments");
            }
            if (!declared.isEmpty() && !declared.contains(candidate)) {
                throw AiAssistantLoopException.malformed(RECORD_KIND_OUTSIDE_SCOPE);
            }
            return candidate;
        }
        if (declared.size() == 1) {
            return declared.getFirst();
        }
        if (!scope.stageIds().isEmpty() || !scope.dealStatuses().isEmpty()) {
            return DEAL;
        }
        if (contextKind != null && KINDS.contains(contextKind)
                && (declared.isEmpty() || declared.contains(contextKind))) {
            return contextKind;
        }
        if (declared.size() > 1) {
            // One cohort read covers one kind. Picking a listed kind here would let the preview
            // count contacts while the executed turn, narrowing the same declaration by a tool
            // argument, read companies — and the confirmed number would describe neither. Counting
            // every declared kind is a different retrieval contract than this one implements, so the
            // honest bound is to require the declaration to resolve to a single kind first.
            throw AiAssistantLoopException.malformed(RECORD_KIND_AMBIGUOUS);
        }
        return "company";
    }
}

package ooo.klae.connex.backend.ai.brief;

import java.util.Set;

/**
 * Server-side target of one positional deal-brief source id.
 * @param kind stable record kind
 * @param id real workspace-scoped record id that never enters the provider prompt
 */
public record DealBriefSource(String kind, int id) {
    private static final Set<String> KINDS = Set.of("deal", "person", "act", "note", "task");

    public DealBriefSource {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported deal brief source kind");
        }
        if (id <= 0) {
            throw new IllegalArgumentException("Deal brief source id must be positive");
        }
    }
}

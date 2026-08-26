package ooo.klae.connex.backend.ai.assistant;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The watch types this build evaluates, and the shape of the threshold each one declares.
 *
 * <p>Every type here reads a condition a source-owned system already computes. Radar's warmth model
 * owns bands, trends, and recency; the task projection owns overdue; the deterministic deal risk
 * model owns risk level and factor codes. Ask Connex adds a threshold, a cooldown, and a delivery —
 * never a new signal.
 *
 * <p>Five of the nine watch types named by the parent issue are deliberately absent. Two of them are
 * out of scope for this increment rather than impossible, and the note says which is which:
 *
 * <ul>
 *   <li><strong>Stakeholder coverage loss / single-threaded deal.</strong> The risk model exposes
 *       {@code no_stakeholders} and {@code stakeholder_cold} as point-in-time factors, which
 *       {@link #DEAL_RISK_THRESHOLD} already covers, but "became single-threaded" is a delta over a
 *       stakeholder-count history nothing persists.</li>
 *   <li><strong>Champion role or employer change.</strong> Deferred by scope, not by capability.
 *       Employment history is a source-owned timeline and the {@code last_fired_state} token
 *       mechanism could carry the current employer as its token, which is exactly how a change would
 *       be detected without a second signal state. It is left out because this increment ships the
 *       four threshold-shaped types and their evaluation contract, not because the signal is
 *       missing.</li>
 *   <li><strong>Configured field or state change.</strong> Also deferred by scope. Deal stage changes
 *       already have a journal in {@code deal_stage_history}, so a stage-change watch is buildable
 *       today; what does not exist is a <em>general</em> field-diff journal covering arbitrary
 *       configured fields, and shipping only stages under a general name would be a watch whose name
 *       overstates what it evaluates.</li>
 *   <li><strong>Meeting approaching without a prepared brief.</strong> Connex has no meeting entity
 *       and no preparation state, so the condition has no source of truth to read.</li>
 *   <li><strong>Saved view or report result crossing a threshold.</strong> Needs bounded repeated
 *       evaluation of an arbitrary saved view plus its own result-history state.</li>
 * </ul>
 */
public enum AiWatchType {

    /** Fires when the warmth model reports the subject at or below a declared band. */
    RELATIONSHIP_COOLING("relationship_cooling", Set.of("person", "company"), Threshold.BAND),

    /** Fires when the warmth model reports no qualifying touch for a declared number of days. */
    NO_INTERACTION("no_interaction", Set.of("person", "company"), Threshold.DAYS),

    /** Fires when an open task linked to the subject is past its due date. */
    COMMITMENT_OVERDUE("commitment_overdue", Set.of("person", "company", "deal"), Threshold.NONE),

    /** Fires when the deterministic risk model reports the deal at or above a declared level. */
    DEAL_RISK_THRESHOLD("deal_risk_threshold", Set.of("deal"), Threshold.LEVEL);

    /** Which declared threshold column a type uses. */
    public enum Threshold { BAND, DAYS, LEVEL, NONE }

    private final String key;
    private final Set<String> subjectKinds;
    private final Threshold threshold;

    AiWatchType(String key, Set<String> subjectKinds, Threshold threshold) {
        this.key = key;
        this.subjectKinds = Set.copyOf(subjectKinds);
        this.threshold = threshold;
    }

    /** @return the durable key, matching the database check constraint */
    public String key() {
        return key;
    }

    /** @return record kinds this watch may be created against */
    public Set<String> subjectKinds() {
        return subjectKinds;
    }

    /** @return which declared threshold column this type uses */
    public Threshold threshold() {
        return threshold;
    }

    /** @return the type for a durable key, or empty when the key is not evaluated by this build */
    public static Optional<AiWatchType> from(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (AiWatchType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}

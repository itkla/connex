package ooo.klae.connex.backend.beans;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The closed lead-lifecycle vocabulary carried by a contact, together with the transitions the
 * product permits between its stages.
 *
 * <p>A {@code null} stage is a first-class value meaning "not in a lead lifecycle": it is the state
 * of every contact captured as a relationship rather than a prospect, and the state every contact
 * held before the lifecycle existed. Entering the lifecycle and withdrawing from it are ordinary
 * transitions, so {@code null} is handled by {@link #isTransitionAllowed(PersonLifecycleStage,
 * PersonLifecycleStage)} rather than by a sentinel constant.
 *
 * <p>The model and its rationale are documented in {@code docs/LEAD_LIFECYCLE.md} (#559).
 */
public enum PersonLifecycleStage {
    /** Entered the lifecycle and not yet worked. */
    NEW,
    /** Actively being contacted or qualified. */
    WORKING,
    /** Real but not ready; parked deliberately rather than dropped. */
    NURTURING,
    /** Meets the workspace's qualification criteria and is eligible for conversion. */
    QUALIFIED,
    /** Rejected, with a required reason code. */
    DISQUALIFIED,
    /** A deal was created from this contact. */
    CONVERTED,
    /** Previously disqualified or converted, returned to the top of the lifecycle. */
    RECYCLED;

    private static final Set<PersonLifecycleStage> FROM_ENTRY =
        EnumSet.of(NEW);

    private static final Map<PersonLifecycleStage, Set<PersonLifecycleStage>> ALLOWED = Map.of(
        NEW, EnumSet.of(WORKING, NURTURING, QUALIFIED, DISQUALIFIED),
        WORKING, EnumSet.of(NURTURING, QUALIFIED, DISQUALIFIED),
        NURTURING, EnumSet.of(WORKING, QUALIFIED, DISQUALIFIED),
        QUALIFIED, EnumSet.of(WORKING, NURTURING, CONVERTED, DISQUALIFIED),
        DISQUALIFIED, EnumSet.of(RECYCLED),
        CONVERTED, EnumSet.of(RECYCLED),
        RECYCLED, EnumSet.of(NEW, WORKING, NURTURING, QUALIFIED, DISQUALIFIED));

    /**
     * Whether a contact may move directly between two lifecycle states.
     *
     * <p>Withdrawing from the lifecycle — any stage to {@code null} — is always permitted, because a
     * workspace must be able to say "this person is a relationship, not a prospect" at any point.
     * Entering it is permitted only at {@link #NEW}. A transition to the current stage is not a
     * transition and is rejected here; callers treat it as a no-op before asking.
     *
     * @param from current stage, or {@code null} when the contact is not in a lifecycle
     * @param to requested stage, or {@code null} to withdraw from the lifecycle
     * @return whether the move is permitted
     */
    public static boolean isTransitionAllowed(PersonLifecycleStage from, PersonLifecycleStage to) {
        if (from == to) {
            return false;
        }
        if (to == null) {
            return true;
        }
        if (from == null) {
            return FROM_ENTRY.contains(to);
        }
        return ALLOWED.get(from).contains(to);
    }

    /**
     * The stages a contact in the given state may move to, for surfacing the available choices.
     *
     * @param from current stage, or {@code null} when the contact is not in a lifecycle
     * @return permitted destination stages, excluding withdrawal
     */
    public static Set<PersonLifecycleStage> allowedTransitionsFrom(PersonLifecycleStage from) {
        return from == null ? Set.copyOf(FROM_ENTRY) : Set.copyOf(ALLOWED.get(from));
    }
}

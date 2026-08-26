package ooo.klae.connex.backend.beans;

/**
 * The two axes a lead is assessed on, kept deliberately separate (#559).
 *
 * <p>A contact that fits the workspace's ideal customer perfectly but has never replied, and one
 * that engages constantly but could never buy, are different problems with different responses.
 * Collapsing them into a single number hides which one you are looking at, so the epic requires the
 * dimensions stay apart and Connex scores each on its own.
 */
public enum QualificationDimension {

    /** Whether the contact is the right kind of customer at all: need, authority, budget, timing. */
    FIT,

    /** Whether the relationship is actually alive: responsiveness, meetings, momentum. */
    ENGAGEMENT
}

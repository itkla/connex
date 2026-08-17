package ooo.klae.connex.backend.beans;

/**
 * How a contact stands against one qualification criterion (#559).
 *
 * <p>{@link #UNKNOWN} is a real answer, not the absence of one: recording that the team asked and
 * could not find out is different from never having asked. Both leave the criterion unmet — an
 * unanswered question is not evidence of fitness — but only {@code UNKNOWN} says the question was
 * put and returned nothing.
 */
public enum QualificationAnswer {

    /** The criterion is satisfied. */
    MET,

    /** The criterion was assessed and is not satisfied. */
    NOT_MET,

    /** The criterion was assessed and the answer could not be established. */
    UNKNOWN
}

package ooo.klae.connex.backend.services;

/** Admission immediately before the first mutation, after every prerequisite lock and validation. */
@FunctionalInterface
public interface MutationAdmission {
    /** Existing non-assistant callers impose no additional review deadline. */
    MutationAdmission UNRESTRICTED = () -> { };

    /** Admits one atomic action, including a successful unchanged result. */
    void beforeMutation();
}

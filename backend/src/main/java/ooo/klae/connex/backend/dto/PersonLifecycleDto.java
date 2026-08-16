package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;

/**
 * A contact's current lead-lifecycle state plus the stages it may move to next, so a client can
 * render the available choices without duplicating the transition rules.
 */
public record PersonLifecycleDto(
    Integer personId,
    PersonLifecycleStage stage,
    LocalDateTime changedAt,
    PersonDisqualificationReason disqualifiedReason,
    String qualificationNotes,
    List<PersonLifecycleStage> allowedTransitions
) {
    /**
     * Projects the lifecycle state held on a contact.
     *
     * @param person contact to project
     * @return lifecycle state, or {@code null} when there is no contact
     */
    public static PersonLifecycleDto from(Person person) {
        if (person == null) {
            return null;
        }
        return new PersonLifecycleDto(
            person.getId(),
            person.getLifecycleStage(),
            person.getLifecycleChangedAt(),
            person.getDisqualifiedReason(),
            person.getQualificationNotes(),
            PersonLifecycleStage.allowedTransitionsFrom(person.getLifecycleStage()).stream()
                .sorted(Comparator.comparing(Enum::ordinal))
                .toList());
    }
}

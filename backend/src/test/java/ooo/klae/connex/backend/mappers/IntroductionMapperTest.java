package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.Person;

class IntroductionMapperTest extends AbstractMapperTest {

    @Autowired IntroductionMapper introductionMapper;
    @Autowired ActivityMapper activityMapper;

    /**
     * An engaged contact is an introduction candidate until they opt out of intro suggestions;
     * the risk opt-out alone does not remove them.
     */
    @Test
    void introExcludedPersonIsNotACandidate() {
        Person person = newPerson(newCompany());
        engage(person);

        assertTrue(candidateIds().contains(person.getId()));

        personMapper.updateEvaluationExclusions(workspace.getId(), person.getId(), true, null);
        assertTrue(candidateIds().contains(person.getId()));

        personMapper.updateEvaluationExclusions(workspace.getId(), person.getId(), null, true);
        assertFalse(candidateIds().contains(person.getId()));
    }

    private List<Integer> candidateIds() {
        return introductionMapper.findCandidatePersons(workspace.getId()).stream()
            .map(IntroCandidatePerson::getId)
            .toList();
    }

    private void engage(Person person) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("email");
        activity.setSubject("subj_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(newUser());
        activity.setTimestamp("2026-01-01 10:00:00");
        activityMapper.insert(activity);
    }
}

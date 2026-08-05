package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.User;

class IntroductionMapperTest extends AbstractMapperTest {

    @Autowired IntroductionMapper introductionMapper;
    @Autowired ActivityMapper activityMapper;
    @Autowired NoteMapper noteMapper;
    @Autowired PersonEdgeMapper personEdgeMapper;

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

    @Test
    void suspendedPersonIsExcludedFromInteractiveAndReportCandidates() {
        Person person = newPerson(newCompany());
        engage(person);

        assertTrue(candidateIds().contains(person.getId()));
        assertTrue(reportCandidateIds().contains(person.getId()));

        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, false);

        assertFalse(candidateIds().contains(person.getId()));
        assertFalse(reportCandidateIds().contains(person.getId()));
    }

    @Test
    void privateNotesDoNotMakeContactsIntroductionCandidates() {
        Person privateOnly = newPerson(newCompany());
        Person workspaceVisible = newPerson(newCompany());
        User author = newUser();
        addNote(privateOnly, author, "private");
        addNote(workspaceVisible, author, "workspace");

        List<Integer> candidates = candidateIds();

        assertFalse(candidates.contains(privateOnly.getId()));
        assertTrue(candidates.contains(workspaceVisible.getId()));
    }

    @Test
    void graphEvidenceRequiresAnEligibleConnector() {
        Person candidate = newPerson(newCompany());
        Person connector = newPerson(newCompany());
        connect(candidate, connector);

        assertTrue(candidateIds().contains(candidate.getId()));

        personMapper.updateEvaluationExclusions(workspace.getId(), connector.getId(), null, true);

        assertFalse(candidateIds().contains(candidate.getId()));
        assertFalse(reportCandidateIds().contains(candidate.getId()));
    }

    private List<Integer> candidateIds() {
        return introductionMapper.findCandidatePersons(workspace.getId()).stream()
            .map(IntroCandidatePerson::getId)
            .toList();
    }

    private List<Integer> reportCandidateIds() {
        return introductionMapper.findCandidatePersonsForReport(workspace.getId(), 1_000).stream()
            .map(IntroCandidatePerson::getId)
            .toList();
    }

    private void connect(Person first, Person second) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(first.getId(), second.getId()));
        edge.setTargetPersonId(Math.max(first.getId(), second.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);
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

    private void addNote(Person person, User author, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Note " + unique());
        note.setVisibility(visibility);
        note.setAuthor(author);
        note.setPerson(person);
        noteMapper.insert(note);
    }
}

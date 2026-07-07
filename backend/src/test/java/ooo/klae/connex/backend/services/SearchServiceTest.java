package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class SearchServiceTest extends AbstractServiceTest {

    @Autowired private SearchService searchService;
    @Autowired private NoteService noteService;
    @Autowired private TaskService taskService;
    @Autowired private ActivityService activityService;

    @Test
    void blankQueryReturnsEmptyResults() {
        SearchResultsDto results = searchService.search("   ");
        assertTrue(results.getCompanies().isEmpty());
    }

    @Test
    void overlongQueryIsRejected() {
        String tooLong = "a".repeat(201);
        assertThrows(BadRequestException.class, () -> searchService.search(tooLong));
    }

    @Test
    void matchingCompanyIsFoundInTheActiveWorkspace() {
        Company company = newCompany();
        SearchResultsDto results = searchService.search(company.getName());
        assertTrue(results.getCompanies().stream().anyMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void visibleNoteMetadataMatchesRemainSearchable() {
        Note note = new Note();
        note.setContent("metadata-only body " + unique());
        note.setTitle("metadata-only title " + unique());
        note.setVisibility("workspace");
        Note created = noteService.create(note);

        SearchResultsDto results = searchService.search(currentUser.getUsername());
        assertTrue(results.getNotes().stream().anyMatch(result -> result.getId() == created.getId()));
    }

    @Test
    void visibleNoteMetadataMatchesSurviveRedactedContentMatches() {
        String query = currentUser.getUsername();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(query);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        Note source = new Note();
        source.setContent("Visible source [" + query + "](note:" + target.getId() + ")");
        source.setVisibility("workspace");
        Note created = noteService.create(source);

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        SearchResultsDto results = searchService.search(query);
        assertTrue(results.getNotes().stream().anyMatch(result -> result.getId() == created.getId()));
    }

    /**
     * A private note target label embedded in a visible source cannot be used as
     * a global-search oracle by a non-author.
     */
    @Test
    void privateNoteTargetLabels_areNotSearchableByNonAuthor() {
        String label = "Secret Search " + unique();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(label);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        Task task = new Task();
        task.setDescription("Review [" + label + "](note:" + target.getId() + ")");
        task.setAssignedTo(currentUser);
        taskService.create(task);

        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject("Source activity");
        activity.setNotes("Discussed [" + label + "](note:" + target.getId() + ")");
        activityService.create(activity);

        Note sourceNote = new Note();
        sourceNote.setContent("Source note [" + label + "](note:" + target.getId() + ")");
        sourceNote.setVisibility("workspace");
        noteService.create(sourceNote);

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        SearchResultsDto results = searchService.search(label);
        assertTrue(results.getNotes().isEmpty());
        assertTrue(results.getTasks().isEmpty());
        assertTrue(results.getActivities().isEmpty());
    }

    @Test
    void redactedTaskCandidates_doNotHideLaterVisibleMatches() {
        String label = "Visible Search " + unique();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(label);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        for (int i = 0; i < 25; i++) {
            Task hidden = new Task();
            hidden.setDescription("Hidden [" + label + "](note:" + target.getId() + ") " + i);
            hidden.setAssignedTo(currentUser);
            hidden.setDueDate(String.format("2024-01-%02d", i % 28 + 1));
            taskService.create(hidden);
        }

        Task visible = new Task();
        visible.setDescription("Visible task " + label);
        visible.setAssignedTo(currentUser);
        visible.setDueDate("2025-01-01");
        Task created = taskService.create(visible);

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        SearchResultsDto results = searchService.search(label);
        assertTrue(results.getTasks().stream().anyMatch(task -> task.getId() == created.getId()));
    }
}

package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.services.NoteService;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Note} CRUD operations.
 * Accepts and returns {@code NoteDto}. Delegates to {@code NoteService}.
 */

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {
    private final NoteService noteService;

    /**
     * GET endpoint to retrieve notes, with optional filtering by personId or dealId.
     * @param personId
     * @param dealId
     * @return
     */
    @GetMapping
    public List<Note> getNotes(
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId
    ) {
        if (personId != null) return noteService.getNotesByPersonId(personId);
        if (dealId != null)   return noteService.getNotesByDealId(dealId);
        return noteService.getAllNotes();
    }

    /**
     * GET endpoint to retrieve a single note by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Note getNoteById(@PathVariable int id) {
        return noteService.getNoteById(id);
    }

    /**
     * POST endpoint to create a new note.
     * @param note
     * @return
     */
    @PostMapping
    public Note createNote(@RequestBody Note note) {
        return noteService.create(note);
    }

    /**
     * PUT endpoint to update an existing note.
     * @param id
     * @param note
     * @return
     */
    @PutMapping("/{id}")
    public Note updateNote(@PathVariable int id, @RequestBody Note note) {
        return noteService.update(id, note);
    }

    /**
     * DELETE endpoint to delete a note by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteNote(@PathVariable int id) {
        noteService.delete(id);
    }
}

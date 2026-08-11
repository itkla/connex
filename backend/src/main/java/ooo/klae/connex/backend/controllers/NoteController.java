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
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.util.PageBounds;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Note} CRUD operations.
 * Accepts and returns {@code NoteDto}. Delegates to {@code NoteService}.
 */

@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
@TenantJournalAttributable
public class NoteController {
    private final NoteService noteService;

    /**
     * GET endpoint to retrieve notes, with optional filtering by personId or dealId.
     * @param personId
     * @param dealId
     * @param authorId
     * @return
     */
    @GetMapping
    public List<NoteDto> getNotes(
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId,
        @RequestParam(required = false) Integer authorId
    ) {
        List<Note> notes;
        if (personId != null) notes = noteService.getNotesByPersonId(personId);
        else if (dealId != null) notes = noteService.getNotesByDealId(dealId);
        else if (authorId != null) notes = noteService.getNotesByAuthorId(authorId);
        else throw new BadRequestException("A filter is required; use /api/notes/page for workspace-wide lists");
        return notes.stream().map(NoteDto::from).toList();
    }

    /**
     * GET endpoint for a bounded, paginated slice of notes visible to the caller.
     */
    @GetMapping("/page")
    public PageResponse<NoteDto> getNotesPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(defaultValue = "false") boolean workspaceOnly
    ) {
        PageBounds bounds = PageBounds.of(page, size);
        List<NoteDto> items = noteService.getNotesPage(bounds.size(), bounds.offset(), workspaceOnly)
            .stream().map(NoteDto::from).toList();
        return new PageResponse<>(items, noteService.countNotes(workspaceOnly));
    }

    /**
     * GET endpoint to retrieve a single note by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public NoteDto getNoteById(@PathVariable int id) {
        return NoteDto.from(noteService.getNoteById(id));
    }

    /**
     * GET endpoint listing the notes (visible to the caller) that reference a
     * given entity — the note-source backlinks for {@code refType}:{@code refId}.
     */
    @GetMapping("/referencing")
    public List<NoteDto> getNotesReferencing(@RequestParam String refType, @RequestParam int refId) {
        return noteService.getNotesReferencing(refType, refId).stream().map(NoteDto::from).toList();
    }

    /**
     * POST endpoint to create a new note.
     * @param note
     * @return
     */
    @PostMapping
    public NoteDto createNote(@Valid @RequestBody NoteDto dto) {
        return NoteDto.from(noteService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing note.
     * @param id
     * @param note
     * @return
     */
    @PutMapping("/{id}")
    public NoteDto updateNote(@PathVariable int id, @Valid @RequestBody NoteDto dto) {
        return NoteDto.from(noteService.update(id, dto.toBean()));
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

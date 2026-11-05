package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NoteMapper;

class NoteServiceTest extends AbstractServiceTest {

    @Autowired NoteService noteService;
    @Autowired NoteMapper noteMapper;
    @Autowired ObjectMapper objectMapper;

    private Note linkedNote() {
        var company = newCompany();
        var pipeline = newPipeline();
        Note note = draft("Original", null);
        note.setPerson(newPerson(company));
        note.setDeal(newDeal(pipeline, newStage(pipeline, 0), company));
        return noteService.create(note);
    }

    private Note updateFromJson(Note original, String json) {
        return noteService.update(original.getId(), objectMapper.readValue(json, NoteDto.class).toBean());
    }

    @Test
    void applicationObjectMapperRoundTripsCreateAndUpdate() {
        NoteDto createRequest = objectMapper.readValue("{\"content\":\"Original\"}", NoteDto.class);
        assertFalse(createRequest.isPersonSpecified());
        assertFalse(createRequest.isDealSpecified());
        Note created = noteService.create(createRequest.toBean());
        String response = objectMapper.writeValueAsString(NoteDto.from(created));
        var json = objectMapper.readTree(response);
        assertFalse(json.has("person"));
        assertFalse(json.has("deal"));
        assertFalse(json.has("personSpecified"));
        assertFalse(json.has("dealSpecified"));
        NoteDto roundTripped = objectMapper.readValue(response, NoteDto.class);
        assertEquals("Original", roundTripped.getContent());
        assertFalse(roundTripped.isPersonSpecified());
        assertFalse(roundTripped.isDealSpecified());

        NoteDto updateRequest = objectMapper.readValue(
            "{\"content\":\"Edited\",\"person\":null,\"deal\":null}", NoteDto.class);
        assertTrue(updateRequest.isPersonSpecified());
        assertTrue(updateRequest.isDealSpecified());
        Note saved = noteService.update(created.getId(), updateRequest.toBean());
        NoteDto updated = objectMapper.readValue(objectMapper.writeValueAsString(NoteDto.from(saved)), NoteDto.class);
        assertEquals("Edited", updated.getContent());
        assertNull(updated.getPerson());
        assertNull(updated.getDeal());
        assertFalse(updated.isPersonSpecified());
        assertFalse(updated.isDealSpecified());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void update_retainsArchivedContactWithoutRevalidatingUnchangedLinks(boolean sendLinks) {
        Note original = linkedNote();
        assertEquals(1, personMapper.archive(workspace.getId(), original.getPerson().getId()));
        assertEquals(original.getId(), noteService.getNoteById(original.getId()).getId());
        String links = sendLinks
            ? ",\"person\":" + original.getPerson().getId() + ",\"deal\":" + original.getDeal().getId()
            : "";

        Note saved = updateFromJson(original, "{\"content\":\"Edited archived contact note\"" + links + "}");

        assertEquals("Edited archived contact note", saved.getContent());
        assertEquals(original.getPerson().getId(), saved.getPerson().getId());
        assertEquals(original.getDeal().getId(), saved.getDeal().getId());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertEquals(saved.getContent(), found.getContent());
        assertEquals(original.getPerson().getId(), found.getPerson().getId());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    @Test
    void update_rejectsNewlyAssignedArchivedContact() {
        Note original = linkedNote();
        var archived = newPerson(newCompany());
        assertEquals(1, personMapper.archive(workspace.getId(), archived.getId()));

        assertThrows(ResourceNotFoundException.class, () -> updateFromJson(original,
            "{\"content\":\"Unwanted\",\"person\":" + archived.getId() + "}"));

        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertEquals(original.getContent(), found.getContent());
        assertEquals(original.getPerson().getId(), found.getPerson().getId());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    @Test
    void update_omittedLinksSurviveInResponseAndStorage() {
        Note original = linkedNote();
        Note saved = updateFromJson(original, "{\"content\":\"Edited\"}");

        assertEquals(original.getPerson().getId(), saved.getPerson().getId());
        assertEquals(original.getDeal().getId(), saved.getDeal().getId());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertEquals(original.getPerson().getId(), found.getPerson().getId());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    @Test
    void update_explicitNullPersonClearsOnlyPerson() {
        Note original = linkedNote();
        Note saved = updateFromJson(original, "{\"content\":\"Edited\",\"person\":null}");

        assertNull(saved.getPerson());
        assertEquals(original.getDeal().getId(), saved.getDeal().getId());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertNull(found.getPerson());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    @Test
    void update_explicitNullDealClearsOnlyDeal() {
        Note original = linkedNote();
        Note saved = updateFromJson(original, "{\"content\":\"Edited\",\"deal\":null}");

        assertNull(saved.getDeal());
        assertEquals(original.getPerson().getId(), saved.getPerson().getId());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertNull(found.getDeal());
        assertEquals(original.getPerson().getId(), found.getPerson().getId());
    }

    @Test
    void update_explicitNullLinksClearBoth() {
        Note original = linkedNote();
        Note saved = updateFromJson(original, "{\"content\":\"Edited\",\"person\":null,\"deal\":null}");

        assertNull(saved.getPerson());
        assertNull(saved.getDeal());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertNull(found.getPerson());
        assertNull(found.getDeal());
    }

    @Test
    void update_canReplaceOneLinkWithoutClearingTheOther() {
        Note original = linkedNote();
        var replacement = newPerson(newCompany());
        Note saved = updateFromJson(original,
            "{\"content\":\"Edited\",\"person\":" + replacement.getId() + "}");

        assertEquals(replacement.getId(), saved.getPerson().getId());
        assertEquals(original.getDeal().getId(), saved.getDeal().getId());
        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertEquals(replacement.getId(), found.getPerson().getId());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    @Test
    void update_invalidLinkLeavesContentAndLinksUnchanged() {
        Note original = linkedNote();

        assertThrows(ResourceNotFoundException.class, () -> updateFromJson(original,
            "{\"content\":\"Unwanted\",\"person\":2147483647}"));

        Note found = noteMapper.getNoteById(workspace.getId(), original.getId());
        assertEquals(original.getContent(), found.getContent());
        assertEquals(original.getPerson().getId(), found.getPerson().getId());
        assertEquals(original.getDeal().getId(), found.getDeal().getId());
    }

    private Note draft(String content, User spoofedAuthor) {
        Note note = new Note();
        note.setContent(content);
        note.setAuthor(spoofedAuthor);
        return note;
    }

    @Test
    void create_attributesAuthorToSessionUser_ignoringClient() {
        User other = newUser();
        Note created = noteService.create(draft("hi", other));
        Note found = noteMapper.getNoteById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getAuthor().getId());
    }

    @Test
    void conditionalDeleteRefusesChangedStateAndDeletesMatchingState() {
        Note created = noteService.create(draft("conditional", null));

        assertThrows(
            ConflictException.class,
            () -> noteService.deleteIf(created.getId(), current -> false));
        assertEquals(
            "conditional",
            noteMapper.getNoteById(workspace.getId(), created.getId()).getContent());

        noteService.deleteIf(
            created.getId(),
            current -> "conditional".equals(current.getContent()));

        assertNull(noteMapper.getNoteById(workspace.getId(), created.getId()));
    }

    @Test
    void update_preservesOriginalAuthor_ignoringClient() {
        Note created = noteService.create(draft("first", null));
        User other = newUser();
        noteService.update(created.getId(), draft("edited", other));
        Note found = noteMapper.getNoteById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getAuthor().getId());
        assertEquals("edited", found.getContent());
    }
}

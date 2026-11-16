package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NoteDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omittedLinksRemainUnspecified() {
        var note = objectMapper.readValue(
            "{\"content\":\"Text\",\"personSpecified\":true,\"dealSpecified\":true}", NoteDto.class).toBean();

        assertFalse(note.isPersonSpecified());
        assertFalse(note.isDealSpecified());
    }

    @Test
    void explicitNullLinksCannotBeDisguisedAsOmissions() {
        var dto = objectMapper.readValue(
            "{\"content\":\"Text\",\"person\":null,\"deal\":null,\"personSpecified\":false,\"dealSpecified\":false}",
            NoteDto.class);
        var note = dto.toBean();

        assertTrue(note.isPersonSpecified());
        assertTrue(note.isDealSpecified());
        assertNull(note.getPerson());
        assertNull(note.getDeal());
        var serialized = objectMapper.readTree(objectMapper.writeValueAsString(dto));
        assertFalse(serialized.has("personSpecified"));
        assertFalse(serialized.has("dealSpecified"));
        var beanJson = objectMapper.readTree(objectMapper.writeValueAsString(note));
        assertFalse(beanJson.has("personSpecified"));
        assertFalse(beanJson.has("dealSpecified"));
    }
}

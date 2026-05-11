package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteDto {

    private int id;

    @NotBlank
    @Size(max = 4000)
    private String content;

    @NotNull
    @JsonIdentityReference(alwaysAsId = true)
    private User author;

    @JsonIdentityReference(alwaysAsId = true)
    private Person person;

    @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;

    private String createdAt;
    private String updatedAt;

    public static NoteDto from(Note n) {
        if (n == null) return null;
        NoteDto dto = new NoteDto();
        dto.id = n.getId();
        dto.content = n.getContent();
        dto.author = n.getAuthor();
        dto.person = n.getPerson();
        dto.deal = n.getDeal();
        dto.createdAt = n.getCreatedAt();
        dto.updatedAt = n.getUpdatedAt();
        return dto;
    }

    public Note toBean() {
        Note n = new Note();
        n.setId(id);
        n.setContent(content);
        n.setAuthor(author);
        n.setPerson(person);
        n.setDeal(deal);
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(updatedAt);
        return n;
    }
}

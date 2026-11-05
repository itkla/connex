package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;

/**
 * Wire shape for Note. Linked entities are exchanged as plain ids — the FE has
 * never needed the embedded objects, and accepting bare ids avoids the Jackson
 * ObjectId resolver having to find a User/Person/Deal in the same JSON tree
 * during deserialization.
 * Omitted links on update preserve the stored relationship; explicit null clears it.
 */
@Data
@NoArgsConstructor
public class NoteDto {

    private Integer id;

    @NotBlank
    @Size(max = 50000)
    private String content;

    @Size(max = 255)
    private String title;

    @Pattern(regexp = "^(private|workspace)$")
    private String visibility;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer author;

    private Integer person;

    private Integer deal;

    @JsonIgnore
    private boolean personSpecified;

    @JsonIgnore
    private boolean dealSpecified;

    @JsonSetter("person")
    public void setPerson(Integer person) {
        this.person = person;
        this.personSpecified = true;
    }

    @JsonSetter("deal")
    public void setDeal(Integer deal) {
        this.deal = deal;
        this.dealSpecified = true;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdAt;
    private String updatedAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private List<ReferenceDto> references;

    public static NoteDto from(Note n) {
        if (n == null) return null;
        NoteDto dto = new NoteDto();
        dto.id = n.getId();
        dto.content = n.getContent();
        dto.title = n.getTitle();
        dto.visibility = n.getVisibility();
        dto.author = n.getAuthor() != null ? n.getAuthor().getId() : null;
        dto.person = n.getPerson() != null ? n.getPerson().getId() : null;
        dto.deal = n.getDeal() != null ? n.getDeal().getId() : null;
        dto.createdAt = n.getCreatedAt();
        dto.updatedAt = n.getUpdatedAt();
        dto.references = n.getReferences() == null
            ? List.of()
            : n.getReferences().stream().map(ReferenceDto::from).toList();
        return dto;
    }

    public Note toBean() {
        Note n = new Note();
        if (id != null) n.setId(id);
        n.setContent(content);
        n.setTitle(title);
        n.setVisibility(visibility);
        if (author != null) {
            User u = new User();
            u.setId(author);
            n.setAuthor(u);
        }
        if (person != null) {
            Person p = new Person();
            p.setId(person);
            n.setPerson(p);
        } else if (personSpecified) {
            n.setPerson(null);
        }
        if (deal != null) {
            Deal d = new Deal();
            d.setId(deal);
            n.setDeal(d);
        } else if (dealSpecified) {
            n.setDeal(null);
        }
        n.setCreatedAt(createdAt);
        n.setUpdatedAt(updatedAt);
        return n;
    }
}

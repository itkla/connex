package ooo.klae.connex.backend.beans;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a free-text note attached to a {@link Person} or {@link Deal}.
 * Notes are authored by a {@link User} and timestamped.
 * Mapped via {@code NoteMapper} / {@code NoteMapper.xml}.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Note.class)
@Data
@NoArgsConstructor
public class Note {
    private int id;
    private int workspaceId; // owning workspace
    private String content;
    private String title;
    private String visibility;
    @JsonIdentityReference(alwaysAsId = true)
    private User author;
    @JsonIdentityReference(alwaysAsId = true)
    private Person person;
    @JsonIdentityReference(alwaysAsId = true)
    private Deal deal;
    private String createdAt;
    private String updatedAt;
    private List<EntityReference> references;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(int workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public User getAuthor() {
        return author;
    }

    public void setAuthor(User author) {
        this.author = author;
    }

    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
    }

    public Deal getDeal() {
        return deal;
    }

    public void setDeal(Deal deal) {
        this.deal = deal;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}

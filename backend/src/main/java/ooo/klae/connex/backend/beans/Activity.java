package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a logged interaction (e.g. call, email, meeting) with a contact or deal.
 * An Activity is linked to a {@link Person} and/or a {@link Deal}.
 * Mapped via {@code ActivityMapper} / {@code ActivityMapper.xml}.
 * 
 * DO NOT modify the class structure without also updating the database schema in {@code schema.sql} and the MyBatis mappings in {@code ActivityMapper.xml}.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Activity.class)
@Data
@NoArgsConstructor
public class Activity {
    private int id; // auto-incremented primary key
    private String type; // e.g. "call", "email", "meeting"
    private String subject; 
    private String notes; 
    @JsonIdentityReference(alwaysAsId = true)
    private Person person; // target
    @JsonIdentityReference(alwaysAsId = true)
    private Deal deal; // related deal
    @JsonIdentityReference(alwaysAsId = true)
    private User createdBy; // user who created the activity object
    private String timestamp;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}

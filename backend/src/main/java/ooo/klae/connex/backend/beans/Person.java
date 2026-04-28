package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a contact (person) in the CRM.
 * A Person can belong to a {@link Company}, be linked to {@link Deal}s,
 * and have associated {@link Note}s, {@link Activity}s, {@link Task}s, and {@link Tag}s.
 * Mapped via {@code PersonMapper} / {@code PersonMapper.xml}.
 */

@Data
@NoArgsConstructor
public class Person {
    private int id;
    private String name;
    private String email;
    private String phone;
    private Company company;
    private String title;
    private String[] tags;
    private Task[] tasks; // tasks assigned to this contact
    private Activity[] activities; // interactions logged with this contact
    private Deal[] deals; // deals this person is associated with (many-to-many). NOT the same as the company’s deals.
    private Note[] notes;
    private String createdAt;
    private String updatedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public Task[] getTasks() {
        return tasks;
    }

    public void setTasks(Task[] tasks) {
        this.tasks = tasks;
    }

    public Activity[] getActivities() {
        return activities;
    }

    public void setActivities(Activity[] activities) {
        this.activities = activities;
    }

    public Deal[] getDeals() {
        return deals;
    }

    public void setDeals(Deal[] deals) {
        this.deals = deals;
    }

    public Note[] getNotes() {
        return notes;
    }

    public void setNotes(Note[] notes) {
        this.notes = notes;
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

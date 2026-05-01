package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a sales opportunity (deal) being tracked in a pipeline.
 * A Deal belongs to a {@link Pipeline} and sits in a {@link Stage}.
 * It can be linked to a {@link Company}, one or more {@link Person}s,
 * and have associated {@link Activity}s, {@link Note}s, and {@link Task}s.
 * Mapped via {@code DealMapper} / {@code DealMapper.xml}.
 */

@Data
@NoArgsConstructor
public class Deal {
    private int id;
    private String name;
    private double value;
    private String currency; // e.g. "JPY"
    private Pipeline pipeline;
    private Stage stage;
    private Company company;
    private DealPerson[] people;
    private Activity[] activities;
    private Note[] notes;
    private Task[] tasks;
    private Tag[] tags;
    private String expectedCloseDate;
    private String closedAt;
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

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    /**
     * @params Pipeline pipeline - the sales pipeline this deal belongs to
     */
    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public DealPerson[] getPeople() {
        return people;
    }

    public void setPeople(DealPerson[] people) {
        this.people = people;
    }

    public Activity[] getActivities() {
        return activities;
    }

    public void setActivities(Activity[] activities) {
        this.activities = activities;
    }

    public Note[] getNotes() {
        return notes;
    }

    public void setNotes(Note[] notes) {
        this.notes = notes;
    }

    public Task[] getTasks() {
        return tasks;
    }

    public void setTasks(Task[] tasks) {
        this.tasks = tasks;
    }

    public Tag[] getTags() {
        return tags;
    }

    public void setTags(Tag[] tags) {
        this.tags = tags;
    }

    public String getExpectedCloseDate() {
        return expectedCloseDate;
    }

    public void setExpectedCloseDate(String expectedCloseDate) {
        this.expectedCloseDate = expectedCloseDate;
    }

    public String getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(String closedAt) {
        this.closedAt = closedAt;
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

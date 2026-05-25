package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a sales opportunity (deal) being tracked in a pipeline.
 * A Deal belongs to a {@link Pipeline} and sits in a {@link Stage}.
 * It can be linked to a {@link Company}, one or more {@link Person}s,
 * and have associated {@link Activity}s, {@link Note}s, and {@link Task}s.
 * Mapped via {@code DealMapper} / {@code DealMapper.xml}.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Deal.class)
@Data
@NoArgsConstructor
public class Deal {
    private int id;
    private String name;
    private double value;
    private double actualValue;
    private String currency; // e.g. "JPY"
    private Integer pipelineId;
    private Integer stageId;
    private Integer companyId;
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

    public double getActualValue() {
        return actualValue;
    }

    public void setActualValue(double actualValue) {
        this.actualValue = actualValue;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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

    public void setPipelineId(Integer pipelineId) {
        this.pipelineId = pipelineId;
    }

    public void setStageId(Integer stageId) {
        this.stageId = stageId;
    }

    public void setCompanyId(Integer companyId) {
        this.companyId = companyId;
    }

    public Integer getPipelineId() {
        return pipelineId;
    }

    public Integer getStageId() {
        return stageId;
    }

    public Integer getCompanyId() {
        return companyId;
    }
}
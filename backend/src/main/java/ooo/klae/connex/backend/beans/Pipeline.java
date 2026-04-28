package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a named sales pipeline (e.g. "New Business", "Renewals").
 * A Pipeline contains an ordered list of {@link Stage}s, each of which holds {@link Deal}s.
 * Mapped via {@code PipelineMapper} / {@code PipelineMapper.xml}.
 */

@Data
@NoArgsConstructor
public class Pipeline {
    private int id;
    private String name;
    private Stage[] stages;
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

    public Stage[] getStages() {
        return stages;
    }

    public void setStages(Stage[] stages) {
        this.stages = stages;
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

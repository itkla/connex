package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single step within a {@link Pipeline} (e.g. "Qualified", "Proposal Sent", "Closed Won").
 * {@link Deal}s move through Stages as they progress.
 * Shares its mapper with Pipeline: {@code PipelineMapper} / {@code PipelineMapper.xml}.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Stage.class)
@Data
@NoArgsConstructor
public class Stage {
    private int id;
    private String name;
    @JsonIdentityReference(alwaysAsId = true)
    private Pipeline pipeline;
    private int position; // zero-based index of this stage within its pipeline
    private Deal[] deals; // deals currently in this stage

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

    public Pipeline getPipeline() {
        return pipeline;
    }

    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Deal[] getDeals() {
        return deals;
    }

    public void setDeals(Deal[] deals) {
        this.deals = deals;
    }
}

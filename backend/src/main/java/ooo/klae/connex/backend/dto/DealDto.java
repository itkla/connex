package ooo.klae.connex.backend.dto;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealDto {

    private Integer id;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer workspaceId;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer ownerId;

    @NotBlank
    @Size(max = 255)
    private String name;

    @PositiveOrZero
    private double value;

    // not annotating with @PositiveOrZero because actual value can be negative
    private double actualValue;

    @NotBlank
    @Size(max = 8)
    private String currency;

    @NotNull
    private Integer pipeline;

    @NotNull
    private Integer stage;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer position;

    private Integer company;

    @Size(max = 10)
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Expected close date must use YYYY-MM-DD")
    private String expectedCloseDate;

    @Size(max = 32)
    private String closedAt;

    @Size(max = 255)
    private String closedReason;

    // Explicit outcome: TRUE=won, FALSE=lost, NULL=open. Client-settable; the server
    // reconciles closed_at to match (and a terminal stage forces it).
    private Boolean won;

    private int[] personIds;
    private int[] activityIds;
    private int[] noteIds;
    private int[] taskIds;
    private int[] tagIds;

    private String createdAt;
    private String updatedAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private List<ReferenceDto> references;

    public static DealDto from(Deal d) {
        if (d == null) return null;
        DealDto dto = new DealDto();
        dto.id = d.getId();
        dto.workspaceId = d.getWorkspaceId();
        dto.ownerId = d.getOwnerId();
        dto.name = d.getName();
        dto.value = d.getValue();
        dto.actualValue = d.getActualValue();
        dto.currency = d.getCurrency();
        dto.pipeline = d.getPipelineId();
        dto.stage = d.getStageId();
        dto.position = d.getPosition();
        dto.company = d.getCompanyId();
        dto.expectedCloseDate = d.getExpectedCloseDate();
        dto.closedAt = d.getClosedAt();
        dto.closedReason = d.getClosedReason();
        dto.won = d.getWon();

        // Hunter's note: i genuinely forgot how i made this. stackoverflow? idk but it's hard to read but once you understand it it works
        dto.personIds = d.getPeople() == null ? null : Arrays.stream(d.getPeople())
            .filter(dp -> dp.getPerson() != null) // if person in lookup not null map
            .mapToInt(dp -> dp.getPerson().getId()).toArray(); // is each person from getPeople null? yes : no, then map to array of person ids.map each person's deals so each person has an array of deal ids || null
        dto.activityIds = d.getActivities() == null ? null : Arrays.stream(d.getActivities()).mapToInt(Activity::getId).toArray();
        dto.noteIds = d.getNotes() == null ? null : Arrays.stream(d.getNotes()).mapToInt(Note::getId).toArray();
        dto.taskIds = d.getTasks() == null ? null : Arrays.stream(d.getTasks()).mapToInt(Task::getId).toArray();
        dto.tagIds = d.getTags() == null ? null : Arrays.stream(d.getTags()).mapToInt(Tag::getId).toArray();
        dto.createdAt = d.getCreatedAt();
        dto.updatedAt = d.getUpdatedAt();
        dto.references = d.getReferences() == null
            ? List.of()
            : d.getReferences().stream().map(ReferenceDto::from).toList();
        return dto;
    }

    public Deal toBean() {
        Deal d = new Deal();
        if (id != null) d.setId(id);
        if (workspaceId != null) d.setWorkspaceId(workspaceId);
        d.setOwnerId(ownerId);
        d.setName(name);
        d.setValue(value);
        // if (actualValue != null && actualValue != 0) d.setActualValue(actualValue);
        d.setActualValue(actualValue);
        d.setCurrency(currency);
        d.setPipelineId(pipeline);
        d.setStageId(stage);
        d.setCompanyId(company);
        d.setExpectedCloseDate(expectedCloseDate);
        d.setClosedAt(closedAt);
        d.setClosedReason(closedReason);
        d.setWon(won);
        d.setCreatedAt(createdAt);
        d.setUpdatedAt(updatedAt);
        return d;
    }
}
package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal value;

    @Digits(integer = 13, fraction = 2)
    private BigDecimal actualValue;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String valueSource;

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

    @Size(max = 1000)
    private String closedReason;

    private Boolean won;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean riskExcluded;

    private int[] personIds;
    private int[] activityIds;
    private int[] noteIds;
    private int[] taskIds;
    private int[] tagIds;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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
        dto.value = money(d.getValue());
        dto.actualValue = money(d.getActualValue());
        dto.valueSource = d.getValueSource();
        dto.currency = d.getCurrency();
        dto.pipeline = d.getPipelineId();
        dto.stage = d.getStageId();
        dto.position = d.getPosition();
        dto.company = d.getCompanyId();
        dto.expectedCloseDate = d.getExpectedCloseDate();
        dto.closedAt = d.getClosedAt();
        dto.closedReason = d.getClosedReason();
        dto.won = d.getWon();
        dto.riskExcluded = d.isRiskExcluded();

        dto.personIds = d.getPeople() == null ? null : Arrays.stream(d.getPeople())
            .filter(dp -> dp.getPerson() != null)
            .mapToInt(dp -> dp.getPerson().getId()).toArray();
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
        d.setValue(money(value));
        d.setActualValue(money(actualValue));
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

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }
}

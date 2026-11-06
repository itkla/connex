package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Validated filters shared by the bounded activity and task list reads. */
@Data
@NoArgsConstructor
public class ActivityListFilter {
    @Positive private Integer personId;
    @Positive private Integer companyId;
    @Positive private Integer dealId;
    @Positive private Integer createdById;
    @Positive private Integer assignedToId;
    @Size(max = 50) private List<@Positive Integer> persons;
    @Size(max = 50) private List<@Positive Integer> companies;
    @Size(max = 50) private List<@Positive Integer> deals;
    @Size(max = 50) private List<@Positive Integer> members;
    @Size(max = 200) private String q;
    @Pattern(regexp = "all|Call|Email|Meeting|Note|Other") private String type;
    @Pattern(regexp = "myOpen|dueToday|overdue|unassigned|allOpen|completed|all") private String queue;
    /** Returns a literal contains pattern so search metacharacters cannot widen the match. */
    public String getSearchPattern() {
        return ooo.klae.connex.backend.util.LikePattern.containing(q == null ? "" : q.trim());
    }
}


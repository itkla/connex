package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One THEN action. {@code type} selects the kind ({@code create_task}, {@code log_activity},
 * {@code add_tag}, {@code notify}); the remaining fields carry that type's configuration and are
 * validated per type by the service. Record-mutating action types are deliberately excluded in v1.
 */
@Data
@NoArgsConstructor
public class RuleAction {

    @NotBlank
    @Size(max = 24)
    private String type;

    @Size(max = 255)
    private String title;

    @Size(max = 2000)
    private String body;

    @Size(max = 32)
    private String activityType;

    private Integer tagId;

    private Integer dueInDays;

    @Size(max = 16)
    private String severity;
}

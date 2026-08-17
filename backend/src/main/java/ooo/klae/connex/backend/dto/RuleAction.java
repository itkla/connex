package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One THEN action. {@code type} selects the kind ({@code create_task}, {@code log_activity},
 * {@code add_tag}, {@code notify}, {@code remove_tag}, {@code create_note}, {@code assign_owner},
 * {@code set_response_due}, {@code change_stage}); the remaining fields carry that type's
 * configuration and are validated per
 * type by the service. Every action runs through the tenant- and RBAC-enforcing service for its kind.
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

    /**
     * Hours until a first response is due, for {@code set_response_due} (#559).
     *
     * <p>Omitted from JSON when absent, unlike every other field here. The canonical workflow
     * definition serializes this bean whole and is content-addressed by a stored SHA-256; emitting
     * {@code "dueInHours":null} on every action would rewrite the canonical JSON of every workflow
     * that predates this field, changing its hash. The legacy backfill re-canonicalizes stored
     * definitions and refuses to proceed when the result differs from what is stored, so that shift
     * would fail the backfill for every already-migrated workspace rather than merely churn a hash.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer dueInHours;

    @Size(max = 16)
    private String severity;

    private Integer targetUserId;

    private Integer targetStageId;
}

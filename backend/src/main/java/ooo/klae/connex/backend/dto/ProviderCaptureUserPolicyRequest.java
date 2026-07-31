package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Current-user capture preferences constrained by the workspace ceiling.
 *
 * @param enabled whether capture is requested
 * @param calendar calendar stream selection
 * @param mailInbox Inbox stream selection
 * @param mailSent Sent stream selection
 * @param backfillDays requested backfill window
 * @param includeBodies explicit body-content opt-in
 * @param admissionMode manual, review, or automatic admission
 * @param excludedPeople provider identities excluded from capture
 * @param excludedConversations provider conversation identities excluded from capture
 * @param version optimistic policy version, zero when creating
 */
public record ProviderCaptureUserPolicyRequest(
    boolean enabled,
    boolean calendar,
    boolean mailInbox,
    boolean mailSent,
    @Min(1) @Max(180) int backfillDays,
    boolean includeBodies,
    @NotNull @Pattern(regexp = "manual|review|automatic") String admissionMode,
    @NotNull @Size(max = 100) List<@Size(max = 254) String> excludedPeople,
    @NotNull @Size(max = 100) List<@Size(max = 512) String> excludedConversations,
    @Min(0) long version
) {
    /** Defensively copies explicit user exclusions. */
    public ProviderCaptureUserPolicyRequest {
        excludedPeople = excludedPeople == null
            ? List.of()
            : List.copyOf(excludedPeople);
        excludedConversations = excludedConversations == null
            ? List.of()
            : List.copyOf(excludedConversations);
    }
}

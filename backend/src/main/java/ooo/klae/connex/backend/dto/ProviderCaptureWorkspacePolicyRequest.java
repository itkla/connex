package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Workspace administrator ceiling for one provider.
 */
public record ProviderCaptureWorkspacePolicyRequest(
    boolean allowed,
    boolean calendar,
    boolean mailInbox,
    boolean mailSent,
    @Min(1) @Max(180) int maxBackfillDays,
    boolean bodyCaptureAllowed,
    boolean reviewRequired,
    @AssertTrue(message = "Private events must remain excluded") boolean excludePrivateEvents,
    boolean excludeInternalOnly,
    @NotNull @Size(max = 100) List<@Size(max = 253) String> excludedDomains,
    @Min(0) long version
) {
    /** Defensively copies the domain ceiling. */
    public ProviderCaptureWorkspacePolicyRequest {
        excludedDomains = excludedDomains == null ? List.of() : List.copyOf(excludedDomains);
    }
}

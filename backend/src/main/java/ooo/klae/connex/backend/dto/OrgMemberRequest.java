package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for setting an organization member's role. {@code acknowledgeConfinement} accepts
 * that the change may leave the member privileged without a passkey approved in the organization
 * (#1534); only {@code true} acknowledges. A new owner promotion creates consent while preserving
 * the current membership; its offeree acknowledges confinement when accepting that offer.
 */
@Data
public class OrgMemberRequest {
    @NotBlank
    private String orgRole;
    private Boolean acknowledgeConfinement;
}

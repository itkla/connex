package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * Request to create a shareable invite link. {@code role} defaults to {@code member} when blank;
 * {@code expiresInDays} defaults to 14 when null; {@code maxUses} null means unlimited.
 */
@Data
public class CreateInviteLinkRequest {

    private String role;

    @Positive
    @Max(365)
    private Integer expiresInDays;

    @Positive
    private Integer maxUses;
}

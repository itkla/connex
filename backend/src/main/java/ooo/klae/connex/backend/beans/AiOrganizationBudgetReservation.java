package ooo.klae.connex.backend.beans;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;

/** One bounded provider-call token reservation on the control plane. */
@Data
public class AiOrganizationBudgetReservation {
    private String reservationId;
    private int orgId;
    private LocalDate usageDay;
    private long reservedTokens;
    private LocalDateTime expiresAt;
    private String state;
    private Long consumedTokens;
}

package ooo.klae.connex.backend.beans;

import java.time.LocalDate;

import lombok.Data;

/** One bounded provider-call token reservation on the control plane. */
@Data
public class AiOrganizationBudgetReservation {
    private String reservationId;
    private int orgId;
    private LocalDate usageDay;
    private long reservedTokens;
}

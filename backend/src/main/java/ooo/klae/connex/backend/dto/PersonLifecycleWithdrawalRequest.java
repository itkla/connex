package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A requested withdrawal of a contact from the lead lifecycle.
 *
 * <p>The note travels in a validated body rather than a query parameter: it is free text a user
 * wrote about a real person, and a URL would leak it into access logs and proxy traces.
 */
@Data
@NoArgsConstructor
public class PersonLifecycleWithdrawalRequest {
    @Size(max = 2000)
    private String note;
}

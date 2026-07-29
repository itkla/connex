package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Eligible person scalar values read by the canonical identity backfill.
 */
@Data
@NoArgsConstructor
public class PersonIdentityBackfillCandidate {
    private int id;
    private String email;
    private String phone;
    private LocalDateTime createdAt;
}

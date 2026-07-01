package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A company's state within a single replay frame. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplayCompanyDto {
    /** Company id. */
    private int id;
    /** Warmth band as of the frame: {@code "hot" | "warm" | "cool" | "cold"}. */
    private String band;
}

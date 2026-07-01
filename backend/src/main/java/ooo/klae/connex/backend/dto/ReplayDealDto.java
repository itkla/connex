package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A deal's state within a single replay frame. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplayDealDto {
    /** Deal id. */
    private int id;
    /** Outcome as of the frame: {@code "open" | "won" | "lost"} (a deal closed after the frame is still "open"). */
    private String resolution;
}

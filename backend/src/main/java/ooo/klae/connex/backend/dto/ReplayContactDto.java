package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A contact's state within a single replay frame. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplayContactDto {
    /** Contact (person) id. */
    private int id;
    /** Warmth band as of the frame: {@code "hot" | "warm" | "cool" | "cold"}. */
    private String band;
    /** Id of the company the contact worked at as of the frame, or {@code null} if none/unknown. */
    private Integer employerId;
}

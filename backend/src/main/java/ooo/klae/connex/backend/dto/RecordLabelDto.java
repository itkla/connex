package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A minimal record reference — the id and a display label — used to show a small sample of the
 * records a rule preview matched.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordLabelDto {
    private int id;
    private String label;
}

package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Outcome of a committed import: counts plus the rows that failed validation or insertion. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportResult {
    private int created;
    private int updated;
    private int skipped;
    private List<RowError> failed;
}

package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A filter facet bucket with a stable key, result count, and optional display label.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FacetCount {
    private String key;
    private long count;
    private String label;
}

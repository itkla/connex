package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single filter facet bucket: a key (entity type, file kind, or tag id) and how
 * many attachments fall under it. Auto-mapped from {@code key} / {@code count} columns.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FacetCount {
    private String key;
    private long count;
}
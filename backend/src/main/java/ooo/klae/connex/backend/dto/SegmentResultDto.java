package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The ids of records matching an evaluated set of smart-segment predicates.
 * The client intersects these with its already-filtered list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SegmentResultDto {
    private List<Integer> ids;
}

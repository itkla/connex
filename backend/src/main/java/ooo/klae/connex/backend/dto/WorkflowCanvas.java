package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Per-node canvas positions and the editor viewport for a workflow graph. */
public record WorkflowCanvas(
    Map<String, Position> positions,
    Viewport viewport
) {

    /** A finite two-dimensional node position. */
    public record Position(BigDecimal x, BigDecimal y) { }

    /** A finite editor viewport with bounded zoom. */
    public record Viewport(BigDecimal x, BigDecimal y, BigDecimal zoom) { }
}

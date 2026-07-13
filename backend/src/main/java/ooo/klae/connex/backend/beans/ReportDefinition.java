package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-shared report definition. {@code configJson} contains the typed
 * widget, filter, range, bucket, and layout configuration validated by the
 * report service. Mapped via {@code ReportMapper} / {@code ReportMapper.xml}.
 */
@Data
@NoArgsConstructor
public class ReportDefinition {
    private int id;
    private int workspaceId;
    private String name;
    private String description;
    private String cadence;
    private String templateKey;
    private String configJson;
    private Integer createdBy;
    private String createdAt;
    private String updatedAt;
}

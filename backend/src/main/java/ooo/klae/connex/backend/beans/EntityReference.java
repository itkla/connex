package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A structured @/# reference extracted from an entity's prose field token
 * {@code [Label](type:id)}. {@code sourceType}/{@code sourceId} identify the
 * entity the reference appears in (e.g. {@code note}, {@code task});
 * {@code refType} is one of {@code user}, {@code person}, {@code deal},
 * {@code company} and {@code refId} is the referenced entity's ID (polymorphic
 * across {@code refType}). The frozen {@code label} preserves the display text as
 * authored. Mapped via {@code EntityReferenceMapper} / {@code EntityReferenceMapper.xml}.
 */
@Data
@NoArgsConstructor
public class EntityReference {
    private int workspaceId;
    private String sourceType;
    private int sourceId;
    private String refType;
    private int refId;
    private String label;
    private String createdAt;
}

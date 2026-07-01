package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A structured @-reference extracted from a {@link Note}'s content token
 * {@code [Label](type:id)}. {@code refType} is one of {@code user},
 * {@code person}, {@code deal}, {@code company} and {@code refId} is the
 * referenced entity's ID (polymorphic across {@code refType}). The frozen
 * {@code label} preserves the display text as authored. Mapped via
 * {@code NoteReferenceMapper} / {@code NoteReferenceMapper.xml}.
 */
@Data
@NoArgsConstructor
public class NoteReference {
    private int workspaceId;
    private int noteId;
    private String refType;
    private int refId;
    private String label;
    private String createdAt;
}

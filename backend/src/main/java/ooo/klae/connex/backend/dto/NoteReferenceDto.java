package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.NoteReference;

/**
 * Wire shape for an inline @-reference resolved from a {@link ooo.klae.connex.backend.beans.Note}'s
 * content. {@code type} is one of {@code user}, {@code person}, {@code deal},
 * {@code company}; {@code id} is the referenced entity and {@code label} is the
 * frozen display text the frontend renders as a chip.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteReferenceDto {
    private String type;
    private int id;
    private String label;

    public static NoteReferenceDto from(NoteReference reference) {
        return new NoteReferenceDto(reference.getRefType(), reference.getRefId(), reference.getLabel());
    }
}

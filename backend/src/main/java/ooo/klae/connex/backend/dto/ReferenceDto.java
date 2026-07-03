package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.EntityReference;

/**
 * Wire shape for an inline @/# reference resolved from an entity's prose field.
 * {@code type} is one of {@code user}, {@code person}, {@code deal},
 * {@code company}; {@code id} is the referenced entity and {@code label} is the
 * frozen display text the frontend renders as a chip.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceDto {
    private String type;
    private int id;
    private String label;

    public static ReferenceDto from(EntityReference reference) {
        return new ReferenceDto(reference.getRefType(), reference.getRefId(), reference.getLabel());
    }
}

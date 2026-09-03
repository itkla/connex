package ooo.klae.connex.backend.dto.sequence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Immutable published sequence version.
 *
 * @param version version number within the sequence
 * @param definitionHash lowercase SHA-256 hexadecimal digest
 * @param steps frozen canonical steps
 * @param publishedById optional publisher projected from mutable attribution after account erasure
 * @param createdAt publish time
 */
public record SequenceVersionDto(
        int version,
        String definitionHash,
        List<SequenceStepDto> steps,
        Integer publishedById,
        LocalDateTime createdAt) {
}

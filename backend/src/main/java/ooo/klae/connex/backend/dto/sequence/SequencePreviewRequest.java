package ooo.klae.connex.backend.dto.sequence;

import jakarta.validation.constraints.Positive;

/** Preview target request. */
public record SequencePreviewRequest(@Positive int personId) {
}

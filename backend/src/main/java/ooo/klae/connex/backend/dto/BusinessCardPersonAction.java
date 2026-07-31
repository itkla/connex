package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Explicit person decision for a confirmed business-card import.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BusinessCardPersonAction.Create.class, name = "create"),
    @JsonSubTypes.Type(value = BusinessCardPersonAction.Existing.class, name = "existing")
})
public sealed interface BusinessCardPersonAction {
    /**
     * Create a new contact from the reviewed values.
     */
    record Create() implements BusinessCardPersonAction {
    }

    /**
     * Attach the scan to the exact reviewed existing contact.
     *
     * @param personId existing contact identifier
     * @param duplicateReviewToken token from the exact accepted person duplicate review
     */
    record Existing(
            @NotNull @Positive Integer personId,
            @NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken)
            implements BusinessCardPersonAction {
    }
}

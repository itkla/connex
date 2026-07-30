package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Explicit company decision for a confirmed business-card import.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BusinessCardCompanyAction.Existing.class, name = "existing"),
    @JsonSubTypes.Type(value = BusinessCardCompanyAction.Create.class, name = "create"),
    @JsonSubTypes.Type(value = BusinessCardCompanyAction.None.class, name = "none")
})
public sealed interface BusinessCardCompanyAction {
    /**
     * Link the contact to a visible existing company.
     *
     * @param companyId existing company identifier
     */
    record Existing(@NotNull @Positive Integer companyId) implements BusinessCardCompanyAction {
    }

    /**
     * Create and link a new company.
     *
     * @param companyName confirmed company name
     * @param duplicateReviewToken token from the explicitly accepted company duplicate review
     */
    record Create(
            @NotBlank @Size(max = 255) String companyName,
            @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken)
            implements BusinessCardCompanyAction {

        public Create(String companyName) {
            this(companyName, null);
        }
    }

    /**
     * Leave the imported contact unlinked.
     */
    record None() implements BusinessCardCompanyAction {
    }
}

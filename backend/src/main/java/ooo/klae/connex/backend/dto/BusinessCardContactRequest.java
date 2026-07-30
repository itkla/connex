package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Confirmed contact values submitted with a business-card import.
 *
 * @param name contact name
 * @param email contact email
 * @param phone contact phone
 * @param title contact title
 * @param companyId compatibility field that must agree with an existing-company action
 * @param duplicateReviewToken token from the explicitly accepted person duplicate review
 */
public record BusinessCardContactRequest(
        @NotBlank @Size(max = 255) String name,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String phone,
        @Size(max = 128) String title,
        @Positive Integer companyId,
        @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken) {

    public BusinessCardContactRequest(
            String name,
            String email,
            String phone,
            String title,
            Integer companyId) {
        this(name, email, phone, title, companyId, null);
    }
}

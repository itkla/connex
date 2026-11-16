package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * Bounded person values to check before creation.
 *
 * @param name optional person name
 * @param emails candidate email values
 * @param phones candidate phone values
 * @param reviewToken previously acknowledged bounded-person proof, or null
 */
public record PersonDuplicatePreflightRequest(
        @Size(max = 255) String name,
        @Size(max = 8) List<@Size(max = 2048) String> emails,
        @Size(max = 8) List<@Size(max = 2048) String> phones,
        @Pattern(regexp = "^[0-9a-f]{64}$") String reviewToken) {

    /** Creates a probe without an existing acknowledgement. */
    public PersonDuplicatePreflightRequest(String name, List<String> emails, List<String> phones) {
        this(name, emails, phones, null);
    }
}

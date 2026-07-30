package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Bounded person values to check before creation.
 *
 * @param name optional person name
 * @param emails candidate email values
 * @param phones candidate phone values
 */
public record PersonDuplicatePreflightRequest(
        @Size(max = 255) String name,
        @Size(max = 8) List<@Size(max = 2048) String> emails,
        @Size(max = 8) List<@Size(max = 2048) String> phones) {
}

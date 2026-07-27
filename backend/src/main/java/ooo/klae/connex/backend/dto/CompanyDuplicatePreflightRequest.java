package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Bounded company values to check before creation.
 *
 * @param name optional company name
 * @param websites candidate website or domain values
 * @param phones candidate phone values
 * @param externalIds candidate source-system identifiers
 */
public record CompanyDuplicatePreflightRequest(
        @Size(max = 255) String name,
        @Size(max = 8) List<@Size(max = 2048) String> websites,
        @Size(max = 8) List<@Size(max = 2048) String> phones,
        @Size(max = 8) List<@Size(max = 2048) String> externalIds) {
}

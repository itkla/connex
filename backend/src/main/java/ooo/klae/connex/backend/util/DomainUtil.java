package ooo.klae.connex.backend.util;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Shared email-domain normalization for the join allowlists. Centralized so the per-workspace and
 * per-organization allowlists agree on exactly what a domain is — a divergence between the two would
 * let a domain be accepted at one layer and rejected at the other.
 */
public final class DomainUtil {

    private DomainUtil() {
    }

    /**
     * Normalizes and validates an allowlist domain: trimmed, lowercased, a leading {@code @}
     * stripped, and required to look like a domain.
     * @param domainRaw the submitted domain
     * @return the normalized domain
     * @throws BadRequestException when the value is blank or not domain-shaped
     */
    public static String normalize(String domainRaw) {
        if (domainRaw == null || domainRaw.isBlank()) {
            throw new BadRequestException("Domain is required");
        }
        String domain = domainRaw.trim().toLowerCase();
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        if (domain.isEmpty() || domain.contains("@") || domain.contains(" ") || !domain.contains(".")) {
            throw new BadRequestException("Enter a valid domain such as example.com");
        }
        return domain;
    }

    /**
     * The lowercased domain part of an email address, or the empty string when it has no {@code @}.
     * @param email the email address
     * @return the domain, matchable against a normalized allowlist
     */
    public static String of(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 ? email.substring(at + 1).trim().toLowerCase() : "";
    }
}

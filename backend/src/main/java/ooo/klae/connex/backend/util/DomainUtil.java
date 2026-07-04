package ooo.klae.connex.backend.util;

import java.net.IDN;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Shared email-domain normalization for the join allowlists. Centralized so the per-workspace and
 * per-organization allowlists agree on exactly what a domain is — a divergence between the two would
 * let a domain be accepted at one layer and rejected at the other. Domains are canonicalized to
 * ASCII (IDNA punycode) so an internationalized domain is stored and matched as one stable ASCII
 * value regardless of the Unicode form a user typed, and utf8mb4 collation cannot make distinct
 * internationalized domains compare equal.
 */
public final class DomainUtil {

    private DomainUtil() {
    }

    /**
     * Normalizes and validates an allowlist domain: trimmed, lowercased, a leading {@code @}
     * stripped, required to look like a domain, and canonicalized to ASCII (punycode).
     * @param domainRaw the submitted domain
     * @return the normalized ASCII domain
     * @throws BadRequestException when the value is blank, not domain-shaped, or a malformed IDN
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
        try {
            return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Enter a valid domain such as example.com");
        }
    }

    /**
     * The ASCII (punycode) domain part of an email address, or the empty string when it has no
     * {@code @}. Applies the same canonicalization as {@link #normalize} so a Unicode address matches
     * a stored punycode domain; a malformed domain returns as-is (never throws — this runs on the
     * login/join path) and simply fails to match any allowlist entry.
     * @param email the email address
     * @return the ASCII domain, matchable against a normalized allowlist
     */
    public static String of(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return "";
        }
        String host = email.substring(at + 1).trim().toLowerCase();
        if (host.isEmpty()) {
            return "";
        }
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return host;
        }
    }
}

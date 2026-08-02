package ooo.klae.connex.backend.mail;

import java.net.IDN;
import java.util.List;
import java.util.Locale;

import javax.naming.NamingException;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.Dns;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto.DnsRecord;

/**
 * Derives content-free SPF and DMARC advisory status from independent bounded TXT lookups.
 * Workspace administrators can influence the queried sender domain in override mode, so
 * names are strictly validated, failures are isolated per record, raw TXT is discarded,
 * and DKIM remains not configured until the product has an explicit selector field.
 */
@Service
@RequiredArgsConstructor
public class MailDnsDiagnosticsService {
    private static final int MAX_RECORDS = 64;
    private static final int MAX_RECORD_LENGTH = 4096;

    private final DnsTxtResolver resolver;

    /**
     * Builds advisory DNS metadata without allowing resolver failure to escape.
     *
     * @param senderAddress effective sender address
     * @return content-free DNS status
     */
    public Dns diagnose(String senderAddress) {
        String domain = senderDomain(senderAddress);
        if (domain == null) {
            return new Dns(
                    true,
                    null,
                    unknown(""),
                    notConfigured(),
                    unknown(""));
        }
        return new Dns(
                true,
                domain,
                lookup(domain, "v=spf1"),
                notConfigured(),
                lookup("_dmarc." + domain, "v=dmarc1"));
    }

    /**
     * Returns a normalized validated sender domain, or null for a non-address value.
     *
     * @param senderAddress effective sender address
     * @return normalized ASCII domain, or null
     */
    public static String senderDomain(String senderAddress) {
        if (senderAddress == null || senderAddress.isBlank()) {
            return null;
        }
        String trimmed = senderAddress.trim();
        int separator = trimmed.lastIndexOf('@');
        if (separator <= 0 || separator != trimmed.indexOf('@')
                || separator == trimmed.length() - 1) {
            return null;
        }
        String domain;
        try {
            domain = IDN.toASCII(trimmed.substring(separator + 1), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")) {
            return null;
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")) {
                return null;
            }
        }
        return domain;
    }

    private DnsRecord lookup(String queryName, String requiredPrefix) {
        List<String> records;
        try {
            records = resolver.resolveTxt(queryName);
        } catch (NamingException | RuntimeException exception) {
            return unknown(queryName);
        }
        if (records == null || records.size() > MAX_RECORDS) {
            return unknown(queryName);
        }
        boolean present = false;
        for (String record : records) {
            if (record == null || record.length() > MAX_RECORD_LENGTH) {
                return unknown(queryName);
            }
            if (hasVersionToken(record, requiredPrefix)) {
                present = true;
            }
        }
        return new DnsRecord(queryName, present ? "present" : "missing", records.size());
    }

    private static boolean hasVersionToken(String record, String requiredPrefix) {
        String normalized = record.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(requiredPrefix)) {
            return false;
        }
        if (normalized.length() == requiredPrefix.length()) {
            return true;
        }
        char boundary = normalized.charAt(requiredPrefix.length());
        return "v=spf1".equals(requiredPrefix)
                ? Character.isWhitespace(boundary)
                : boundary == ';';
    }

    private static DnsRecord unknown(String queryName) {
        return new DnsRecord(queryName, "unknown", 0);
    }

    private static DnsRecord notConfigured() {
        return new DnsRecord("", "not_configured", 0);
    }
}

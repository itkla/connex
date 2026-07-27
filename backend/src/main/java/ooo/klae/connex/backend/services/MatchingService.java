package ooo.klae.connex.backend.services;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.springframework.stereotype.Service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import lombok.RequiredArgsConstructor;

/**
 * Pure canonical normalization for identity matching.
 */
@Service
@RequiredArgsConstructor
public class MatchingService {

    private static final int MAX_NAME_CODE_POINTS = 255;
    private static final int MAX_PHONE_INPUT_LENGTH = 256;
    private static final int MAX_ADDRESS_INPUT_LENGTH = 2_048;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_EXTERNAL_ID_LENGTH = 512;
    private static final String EMAIL_LOCAL_SPECIALS = "!#$%&'*+/=?^_`{|}~.-";

    private final PhoneNumberUtil phoneNumberUtil;
    private final PublicSuffixMatcher publicSuffixMatcher;

    /**
     * Normalizes one supported identifier kind.
     * <p>Phone values without an international prefix are resolved against Japan as the default
     * region, so a national-format number belonging to another country is canonicalized as
     * Japanese. Values carrying an explicit country code are unaffected.
     * @param kind identifier kind
     * @param raw acquired value
     * @return the canonical value, or empty when the input is invalid
     */
    public Optional<String> normalizeIdentifier(IdentityKind kind, String raw) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case EMAIL -> normalizeEmail(raw);
            case PHONE -> normalizePhone(raw);
            case DOMAIN -> normalizeDomain(raw);
            case EXTERNAL_ID -> normalizeExternalId(raw);
        };
    }

    /**
     * Normalizes a human name for exact matching without changing name order or punctuation.
     * @param raw authored or imported name
     * @return the canonical name, or empty when the input is invalid
     */
    public Optional<String> normalizeName(String raw) {
        Optional<String> normalizedInput = normalizeUnicode(raw, MAX_ADDRESS_INPUT_LENGTH);
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder folded = new StringBuilder(normalizedInput.orElseThrow().length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalizedInput.orElseThrow().length();) {
            int codePoint = normalizedInput.orElseThrow().codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeSpace(codePoint)) {
                pendingSpace = folded.length() > 0;
                continue;
            }
            if (isForbiddenCodePoint(codePoint)) {
                return Optional.empty();
            }
            if (pendingSpace) {
                folded.append(' ');
                pendingSpace = false;
            }
            appendHiraganaFold(folded, codePoint);
        }
        String result = Normalizer.normalize(
            folded.toString().toLowerCase(Locale.ROOT),
            Normalizer.Form.NFC);
        if (result.isEmpty()
                || result.codePointCount(0, result.length()) > MAX_NAME_CODE_POINTS) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    /**
     * Extracts the registrable company domain from a valid email address.
     * @param rawEmail acquired email value
     * @return the registrable punycode domain, or empty when the email or domain is invalid
     */
    public Optional<String> extractCompanyDomainFromEmail(String rawEmail) {
        Optional<String> email = normalizeEmail(rawEmail);
        if (email.isEmpty()) {
            return Optional.empty();
        }
        String normalized = email.orElseThrow();
        return registrableDomain(normalized.substring(normalized.lastIndexOf('@') + 1));
    }

    private Optional<String> normalizeEmail(String raw) {
        Optional<String> normalizedInput = normalizeUnicode(raw, MAX_ADDRESS_INPUT_LENGTH);
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        String value = normalizedInput.orElseThrow();
        if (containsUnicodeSpaceOrForbidden(value)) {
            return Optional.empty();
        }
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
            return Optional.empty();
        }
        String local = value.substring(0, at);
        if (!isEmailLocalPart(local)) {
            return Optional.empty();
        }
        Optional<String> domain = canonicalHost(value.substring(at + 1), false);
        if (domain.isEmpty()) {
            return Optional.empty();
        }
        String result = local.toLowerCase(Locale.ROOT) + "@" + domain.orElseThrow();
        return result.length() <= MAX_EMAIL_LENGTH ? Optional.of(result) : Optional.empty();
    }

    private Optional<String> normalizePhone(String raw) {
        Optional<String> normalizedInput = normalizeUnicode(raw, MAX_PHONE_INPUT_LENGTH);
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        String value = normalizedInput.orElseThrow();
        StringBuilder parsedInput = new StringBuilder(value.length());
        int parenthesisDepth = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint >= '0' && codePoint <= '9') {
                parsedInput.appendCodePoint(codePoint);
            } else if (isUnicodeSpace(codePoint) || isPhoneSeparator(codePoint)) {
                continue;
            } else if (codePoint == '+') {
                if (parsedInput.length() != 0) {
                    return Optional.empty();
                }
                parsedInput.append('+');
            } else if (codePoint == '(') {
                if (parenthesisDepth != 0) {
                    return Optional.empty();
                }
                parenthesisDepth = 1;
                parsedInput.append('(');
            } else if (codePoint == ')') {
                if (parenthesisDepth != 1) {
                    return Optional.empty();
                }
                parenthesisDepth = 0;
                parsedInput.append(')');
            } else {
                return Optional.empty();
            }
        }
        if (parenthesisDepth != 0) {
            return Optional.empty();
        }
        String parseable = parsedInput.toString();
        if (parseable.startsWith("+81(0)")) {
            parseable = "+81" + parseable.substring(6);
        }
        parseable = parseable.replace("(", "").replace(")", "");
        if (!parseable.startsWith("+") && !parseable.startsWith("0")) {
            return Optional.empty();
        }
        try {
            PhoneNumber number = phoneNumberUtil.parse(parseable, "JP");
            if (number.hasExtension() || !phoneNumberUtil.isValidNumber(number)) {
                return Optional.empty();
            }
            return Optional.of(phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeDomain(String raw) {
        Optional<String> normalizedInput = normalizeUnicode(raw, MAX_ADDRESS_INPUT_LENGTH);
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        String value = normalizedInput.orElseThrow();
        if (containsUnicodeSpaceOrForbidden(value)) {
            return Optional.empty();
        }
        if (value.indexOf('@') >= 0 && !value.contains("://")) {
            return extractCompanyDomainFromEmail(value);
        }
        Optional<String> host = extractHost(value);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> canonical = canonicalHost(host.orElseThrow(), true);
        return canonical.flatMap(this::registrableDomain);
    }

    private Optional<String> normalizeExternalId(String raw) {
        Optional<String> normalizedInput = normalizeUnicode(raw, MAX_ADDRESS_INPUT_LENGTH);
        if (normalizedInput.isEmpty()) {
            return Optional.empty();
        }
        String value = normalizedInput.orElseThrow();
        StringBuilder ascii = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint < 0x21 || codePoint > 0x7e) {
                return Optional.empty();
            }
            ascii.appendCodePoint(codePoint);
        }
        String result = ascii.toString().toLowerCase(Locale.ROOT);
        if (result.isEmpty() || result.length() > MAX_EXTERNAL_ID_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private Optional<String> extractHost(String value) {
        String uriValue;
        if (value.startsWith("//")) {
            uriValue = "https:" + value;
        } else if (value.contains("://")) {
            uriValue = value;
        } else {
            uriValue = "https://" + value;
        }
        try {
            URI uri = new URI(uriValue);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty() || authority.indexOf('%') >= 0
                    || authority.indexOf('@') >= 0 || authority.startsWith("[")) {
                return Optional.empty();
            }
            int parsedPort = uri.getPort();
            if (parsedPort == 0 || parsedPort > 65_535) {
                return Optional.empty();
            }
            String host = uri.getHost();
            if (host != null) {
                return Optional.of(host);
            }
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon || !isValidPort(authority.substring(colon + 1))) {
                    return Optional.empty();
                }
                authority = authority.substring(0, colon);
            }
            return authority.isEmpty() ? Optional.empty() : Optional.of(authority);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> canonicalHost(String rawHost, boolean allowRootDot) {
        String host = rawHost;
        if (allowRootDot && host.endsWith(".") && !host.endsWith("..")) {
            host = host.substring(0, host.length() - 1);
        } else if (host.endsWith(".")) {
            return Optional.empty();
        }
        if (host.isEmpty() || host.indexOf(':') >= 0 || isIpv4(host)) {
            return Optional.empty();
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return Optional.empty();
        }
        StringBuilder asciiHost = new StringBuilder(host.length());
        try {
            for (String label : labels) {
                if (label.isEmpty()) {
                    return Optional.empty();
                }
                String asciiLabel = IDN.toASCII(label, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
                if (asciiLabel.isEmpty() || asciiLabel.length() > 63) {
                    return Optional.empty();
                }
                if (asciiHost.length() > 0) {
                    asciiHost.append('.');
                }
                asciiHost.append(asciiLabel);
            }
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String result = asciiHost.toString();
        if (result.length() > MAX_DOMAIN_LENGTH || isIpv4(result)) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private Optional<String> registrableDomain(String host) {
        String root = publicSuffixMatcher.getDomainRoot(host);
        if (root == null || root.isEmpty()) {
            return Optional.empty();
        }
        String result = root.toLowerCase(Locale.ROOT);
        return result.length() <= MAX_DOMAIN_LENGTH ? Optional.of(result) : Optional.empty();
    }

    private Optional<String> normalizeUnicode(String raw, int maxInputLength) {
        if (raw == null || raw.length() > maxInputLength) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        String trimmed = trimUnicode(normalized);
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private String trimUnicode(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        int end = value.length();
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private void appendHiraganaFold(StringBuilder target, int codePoint) {
        if (codePoint >= 0x30a1 && codePoint <= 0x30f6) {
            target.appendCodePoint(codePoint - 0x60);
        } else if (codePoint == 0x30fd || codePoint == 0x30fe) {
            target.appendCodePoint(codePoint - 0x60);
        } else if (codePoint >= 0x30f7 && codePoint <= 0x30fa) {
            int[] bases = {0x308f, 0x3090, 0x3091, 0x3092};
            target.appendCodePoint(bases[codePoint - 0x30f7]);
            target.appendCodePoint(0x3099);
        } else {
            target.appendCodePoint(codePoint);
        }
    }

    private boolean isEmailLocalPart(String local) {
        if (local.isEmpty() || local.length() > 64
                || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        for (int index = 0; index < local.length(); index++) {
            char character = local.charAt(index);
            if (character > 0x7f
                    || (!Character.isLetterOrDigit(character)
                        && EMAIL_LOCAL_SPECIALS.indexOf(character) < 0)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidPort(String value) {
        if (value.isEmpty() || value.length() > 5) {
            return false;
        }
        int port = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isDigit(character)) {
                return false;
            }
            port = port * 10 + character - '0';
        }
        return port >= 1 && port <= 65_535;
    }

    private boolean isIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int value = 0;
            for (int index = 0; index < part.length(); index++) {
                char character = part.charAt(index);
                if (!Character.isDigit(character)) {
                    return false;
                }
                value = value * 10 + character - '0';
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean containsUnicodeSpaceOrForbidden(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeSpace(codePoint) || isForbiddenCodePoint(codePoint)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnicodeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private boolean isForbiddenCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private boolean isPhoneSeparator(int codePoint) {
        return codePoint == '-'
            || codePoint == '.'
            || codePoint == '/'
            || codePoint == 0x30fb
            || codePoint == 0x30fc
            || (codePoint >= 0x2010 && codePoint <= 0x2014)
            || codePoint == 0x2212;
    }
}

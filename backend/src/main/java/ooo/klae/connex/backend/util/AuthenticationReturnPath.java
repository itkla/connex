package ooo.klae.connex.backend.util;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shared authentication-return contract: a normalized relative path without disguised separators or controls. */
public final class AuthenticationReturnPath {
    private static final Pattern ENCODED_FORBIDDEN = Pattern.compile("%(?:[01][0-9a-f]|7f|2f|5c)|%c2%[89][0-9a-f]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_BYTE = Pattern.compile("%([0-9a-f]{2})", Pattern.CASE_INSENSITIVE);

    private AuthenticationReturnPath() {
    }

    /** Returns the normalized local destination, or null when the supplied address is unsafe or malformed. */
    public static String validate(String candidate) {
        if (!relative(candidate) || forbidden(candidate) || candidate.chars().anyMatch(character -> " \"<>^`{|}[]".indexOf(character) >= 0)) {
            return null;
        }
        try {
            String decoded = decodeForValidation(candidate);
            if (!relative(decoded) || forbidden(decoded)) {
                return null;
            }
            String unreserved = normalizeUnreserved(candidate);
            int query = unreserved.indexOf('?');
            int fragment = unreserved.indexOf('#');
            int end = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
            if (end < 0) end = unreserved.length();
            String path = normalizePath(unreserved.substring(0, end));
            if (!relative(path)) return null;
            URI uri = new URI(path + unreserved.substring(end));
            if (uri.isAbsolute() || uri.getRawAuthority() != null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException | CharacterCodingException exception) {
            return null;
        }
    }

    private static boolean relative(String value) {
        return value != null && value.startsWith("/") && !value.startsWith("//");
    }

    private static boolean forbidden(String value) {
        return value.indexOf('\\') >= 0 || value.chars().anyMatch(character -> character <= 31 || character >= 127 && character <= 159)
                || ENCODED_FORBIDDEN.matcher(value).find();
    }

    private static String decodeForValidation(String value) throws CharacterCodingException {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) != '%') {
                decoded.append(value.charAt(index++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < value.length() && value.charAt(index) == '%') {
                if (index + 2 >= value.length()) throw new IllegalArgumentException("Incomplete encoded byte");
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid encoded byte");
                bytes.write((high << 4) + low);
                index += 3;
            }
            decoded.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
        }
        return decoded.toString();
    }

    private static String normalizeUnreserved(String value) {
        Matcher matcher = ENCODED_BYTE.matcher(value);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            char character = (char) Integer.parseInt(matcher.group(1), 16);
            boolean unreserved = character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || "-._~".indexOf(character) >= 0;
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(unreserved ? String.valueOf(character) : matcher.group()));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private static String normalizePath(String path) {
        String[] segments = path.substring(1).split("/", -1);
        List<String> normalized = new ArrayList<>();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if ("..".equals(segment)) {
                if (!normalized.isEmpty()) normalized.removeLast();
                if (index == segments.length - 1) normalized.add("");
            } else if (".".equals(segment)) {
                if (index == segments.length - 1) normalized.add("");
            } else {
                normalized.add(segment);
            }
        }
        return "/" + String.join("/", normalized);
    }
}

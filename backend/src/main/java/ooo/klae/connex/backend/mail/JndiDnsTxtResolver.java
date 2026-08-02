package ooo.klae.connex.backend.mail;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.springframework.stereotype.Component;

/**
 * JNDI DNS-provider TXT resolver with bounded retry time and content normalization.
 * In workspace override mode an administrator influences the queried sender domain;
 * this implementation therefore requests TXT only, applies low timeout and retry
 * limits, and returns content solely to the in-process status derivation service.
 */
@Component
public class JndiDnsTxtResolver implements DnsTxtResolver {
    private static final String TXT = "TXT";
    private static final int MAX_RECORDS = 64;
    private static final int MAX_RECORD_LENGTH = 4096;

    /**
     * Resolves TXT records through the JDK DNS context and always closes the context.
     *
     * @param queryName validated DNS name
     * @return normalized TXT strings
     * @throws NamingException when lookup, parsing, or cleanup fails
     */
    @Override
    public List<String> resolveTxt(String queryName) throws NamingException {
        requireSafeQueryName(queryName);
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        environment.put(Context.PROVIDER_URL, "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");

        InitialDirContext context = null;
        try {
            context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes(queryName, new String[] { TXT });
            Attribute txt = attributes.get(TXT);
            if (txt == null) {
                return List.of();
            }
            if (txt.size() > MAX_RECORDS) {
                throw malformedResponse();
            }
            List<String> records = new ArrayList<>();
            for (int index = 0; index < txt.size(); index++) {
                String normalized = normalizeTxtValue(txt.get(index));
                if (normalized.length() > MAX_RECORD_LENGTH) {
                    throw malformedResponse();
                }
                records.add(normalized);
            }
            return List.copyOf(records);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    static String normalizeTxtValue(Object value) throws NamingException {
        if (!(value instanceof String raw)) {
            throw malformedResponse();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw malformedResponse();
        }
        if (trimmed.charAt(0) != '"') {
            return trimmed;
        }
        StringBuilder normalized = new StringBuilder();
        int index = 0;
        while (index < trimmed.length()) {
            while (index < trimmed.length() && Character.isWhitespace(trimmed.charAt(index))) {
                index++;
            }
            if (index >= trimmed.length()) {
                break;
            }
            if (trimmed.charAt(index) != '"') {
                throw malformedResponse();
            }
            index++;
            boolean closed = false;
            while (index < trimmed.length()) {
                char current = trimmed.charAt(index++);
                if (current == '\\') {
                    if (index >= trimmed.length()) {
                        throw malformedResponse();
                    }
                    normalized.append(trimmed.charAt(index++));
                } else if (current == '"') {
                    closed = true;
                    break;
                } else {
                    normalized.append(current);
                }
            }
            if (!closed) {
                throw malformedResponse();
            }
        }
        if (normalized.isEmpty()) {
            throw malformedResponse();
        }
        return normalized.toString();
    }

    private static void requireSafeQueryName(String queryName) throws NamingException {
        if (queryName == null || queryName.isBlank() || queryName.length() > 260
                || !queryName.equals(queryName.trim())
                || !queryName.matches("[A-Za-z0-9._-]+")
                || queryName.startsWith(".") || queryName.endsWith(".")
                || queryName.contains("..")) {
            throw malformedResponse();
        }
        for (String label : queryName.split("\\.")) {
            if (label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")
                    || label.contains("_") && !"_dmarc".equalsIgnoreCase(label)) {
                throw malformedResponse();
            }
        }
    }

    private static NamingException malformedResponse() {
        return new NamingException("Malformed TXT response");
    }
}

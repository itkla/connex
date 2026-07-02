package ooo.klae.connex.backend.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Renders transactional email bodies from static HTML templates under
 * {@code classpath:templates/emails/}. Templates are authored as React Email
 * components and rendered to inline-styled, email-client-safe HTML at build time
 * (see {@code frontend/emails}); this renderer only substitutes {@code {{token}}}
 * placeholders. Token values are HTML-escaped, so user-controlled data (workspace
 * or inviter names) cannot inject markup. Files are named
 * {@code <name>.<locale>.html} and fall back to the {@code en} variant.
 */
@Component
public class EmailTemplateRenderer {

    private static final String DEFAULT_LOCALE = "en";
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Renders a template with the given token substitutions.
     * @param templateName the template base name (e.g. {@code invite})
     * @param locale the preferred locale (falls back to {@code en})
     * @param tokens placeholder names to their (unescaped) values
     * @return the rendered HTML
     */
    public String render(String templateName, String locale, Map<String, String> tokens) {
        String template = load(templateName, locale);
        String result = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace("{{" + token.getKey() + "}}", escape(token.getValue()));
        }
        return result;
    }

    private String load(String templateName, String locale) {
        String key = templateName + "." + (locale == null ? DEFAULT_LOCALE : locale);
        return cache.computeIfAbsent(key, k -> {
            String body = readIfPresent("templates/emails/" + k + ".html");
            if (body == null) {
                body = readIfPresent("templates/emails/" + templateName + "." + DEFAULT_LOCALE + ".html");
            }
            if (body == null) {
                throw new IllegalStateException("Email template not found: " + templateName);
            }
            return body;
        });
    }

    private static String readIfPresent(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template: " + path, e);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

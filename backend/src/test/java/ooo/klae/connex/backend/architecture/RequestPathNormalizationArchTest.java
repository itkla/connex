package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps request-scoped policy on the normalized path Spring routes on.
 *
 * <p>{@code HttpServletRequest#getRequestURI()} returns the raw, still-encoded target. A filter or
 * interceptor that decides policy on that value matches a different string from the one the
 * dispatcher resolves a handler with, so percent-encoding a route letter slips past the control
 * while still reaching the handler. Nothing in the type system prevents a new filter from reading
 * the raw value again, so the boundary is pinned here instead.
 */
class RequestPathNormalizationArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern RAW_TARGET_ACCESSOR =
            Pattern.compile("\\b(getRequestURI|getRequestURL|getServletPath|getPathInfo)\\b");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "\\bclass\\s+\\w+(?:<[^>]*>)?\\s*(?:extends\\s+([\\w.]+)(?:<[^>]*>)?\\s*)?"
                    + "(?:implements\\s+([^{]+))?\\{");

    /**
     * The classes permitted to read the raw target.
     *
     * <p>{@code RequestPathNormalizer} is the normalization choke point itself. {@code PublicApiPaths}
     * classifies the namespace for error-shape selection and strips the context and one encoding
     * layer of its own. {@code GlobalExceptionHandler} only reports the target back in a problem
     * body and decides nothing. {@code TenantResolutionInterceptor} still matches policy on the raw
     * target and is allowlisted only until issue #1701 (follow-up to #1643) moves it onto the shared
     * normalizer.
     */
    private static final List<Path> ALLOWED_RAW_URI_SOURCES = List.of(
            Path.of("ooo/klae/connex/backend/config/RequestPathNormalizer.java"),
            Path.of("ooo/klae/connex/backend/exceptions/GlobalExceptionHandler.java"),
            Path.of("ooo/klae/connex/backend/publicapi/PublicApiPaths.java"),
            Path.of("ooo/klae/connex/backend/tenant/TenantResolutionInterceptor.java"));

    @Test
    void requestScopedPolicyClassesDoNotMatchOnTheRawTarget() throws IOException {
        assertEquals(List.of(), unallowedPolicySourcesReadingTheRawTarget());
    }

    @Test
    void theAllowlistShrinksAsClassesMoveOntoTheNormalizer() throws IOException {
        assertEquals(List.of(), staleAllowlistEntries());
    }

    private static List<Path> unallowedPolicySourcesReadingTheRawTarget() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(SOURCE_ROOT::relativize)
                    .filter(path -> !ALLOWED_RAW_URI_SOURCES.contains(path))
                    .filter(path -> readsRawTarget(path))
                    .filter(RequestPathNormalizationArchTest::isRequestPolicySource)
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> staleAllowlistEntries() {
        return ALLOWED_RAW_URI_SOURCES.stream()
                .filter(path -> !Files.exists(SOURCE_ROOT.resolve(path))
                        || !readsRawTarget(path))
                .sorted()
                .toList();
    }

    private static boolean isRequestPolicySource(Path path) {
        return path.startsWith(Path.of("ooo/klae/connex/backend/config"))
                || declaresFilterOrInterceptor(read(path));
    }

    private static boolean declaresFilterOrInterceptor(String source) {
        Matcher matcher = CLASS_DECLARATION.matcher(source);
        while (matcher.find()) {
            if (isPolicySupertype(matcher.group(1))) {
                return true;
            }
            String implemented = matcher.group(2);
            if (implemented != null && Stream.of(implemented.split(","))
                    .anyMatch(RequestPathNormalizationArchTest::isPolicySupertype)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPolicySupertype(String type) {
        if (type == null) {
            return false;
        }
        String name = type.replaceAll("<[^>]*>", "").trim();
        int lastDot = name.lastIndexOf('.');
        String simpleName = lastDot < 0 ? name : name.substring(lastDot + 1);
        return simpleName.endsWith("Filter") || simpleName.endsWith("Interceptor");
    }

    private static boolean readsRawTarget(Path path) {
        return RAW_TARGET_ACCESSOR.matcher(read(path)).find();
    }

    private static String read(Path relativePath) {
        try {
            return Files.readString(SOURCE_ROOT.resolve(relativePath));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect the request path boundary");
        }
    }
}

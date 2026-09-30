package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps application refusals off the container's error dispatch (#1589).
 *
 * <p>{@code HttpServletResponse#sendError} makes a real servlet container ERROR-dispatch the request
 * to {@code /error}. That dispatch re-enters the security chain, where {@code /error} has no rule
 * and falls to {@code anyRequest().authenticated()}, so an anonymous caller receives the entry
 * point's 401 in place of the intended status; MockMvc never performs the dispatch, so only a live
 * container shows it. Until the chain itself admits ERROR dispatches, application code writes every
 * refusal with {@code setStatus} and no main source may call {@code sendError}.
 *
 * <p>The one library refusal that used to escape this rule is the firewall boundary: Spring's
 * {@code HttpStatusRequestRejectedHandler} calls {@code sendError}, so it was replaced with
 * {@code FirewallRefusalHandler} and must not be delegated to again (#1780).
 */
class ErrorDispatchArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern SEND_ERROR_CALL =
            Pattern.compile("\\bsendError\\s*\\(|::\\s*sendError\\b");
    private static final Pattern SEND_ERROR_FIREWALL_HANDLER = Pattern.compile(
            "\\bnew\\s+HttpStatusRequestRejectedHandler\\b"
                    + "|\\bimport\\s+[\\w.]*HttpStatusRequestRejectedHandler\\s*;");

    /**
     * The absence checks below cannot see the likelier regression: {@code FilterChainProxy} defaults
     * its own handler to {@code HttpStatusRequestRejectedHandler}, so deleting the wiring restores
     * the anonymous 401 with no {@code new}, no import and no {@code sendError} anywhere (#1780).
     * A fully qualified inline construction would also slip past the pattern, and this file already
     * uses that idiom elsewhere.
     */
    @Test
    void theFirewallRefusalHandlerStaysWired() throws IOException {
        String config = Files.readString(SOURCE_ROOT.resolve(
                "ooo/klae/connex/backend/config/PublicApiSecurityConfig.java"));
        assertTrue(config.contains("new FirewallRefusalHandler"),
                "the browser plane must keep building its own refusal handler");
        assertTrue(config.contains("requestRejectedHandler("),
                "the handler must stay registered on the web security customizer");
    }

    @Test
    void mainSourcesNeverRefuseWithSendError() throws IOException {
        assertEquals(List.of(), sourcesMatching(SEND_ERROR_CALL));
    }

    @Test
    void mainSourcesNeverDelegateAFirewallRefusalToTheSendErrorHandler() throws IOException {
        assertEquals(List.of(), sourcesMatching(SEND_ERROR_FIREWALL_HANDLER));
    }

    private static List<Path> sourcesMatching(Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> matches(path, pattern))
                    .map(SOURCE_ROOT::relativize)
                    .sorted()
                    .toList();
        }
    }

    private static boolean matches(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect the error dispatch boundary");
        }
    }
}

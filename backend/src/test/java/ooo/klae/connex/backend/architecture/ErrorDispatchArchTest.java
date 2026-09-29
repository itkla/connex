package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * container shows it. Until the chain itself admits ERROR dispatches (#1780), application code
 * writes every refusal with {@code setStatus} and no main source may call {@code sendError}.
 */
class ErrorDispatchArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern SEND_ERROR_CALL =
            Pattern.compile("\\bsendError\\s*\\(|::\\s*sendError\\b");

    @Test
    void mainSourcesNeverRefuseWithSendError() throws IOException {
        assertEquals(List.of(), sourcesCallingSendError());
    }

    private static List<Path> sourcesCallingSendError() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(ErrorDispatchArchTest::callsSendError)
                    .map(SOURCE_ROOT::relativize)
                    .sorted()
                    .toList();
        }
    }

    private static boolean callsSendError(Path path) {
        try {
            return SEND_ERROR_CALL.matcher(Files.readString(path)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect the error dispatch boundary");
        }
    }
}

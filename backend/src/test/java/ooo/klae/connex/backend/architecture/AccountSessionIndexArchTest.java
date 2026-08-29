package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the session index key in one place, on both sides of the store.
 *
 * <p>The write half ({@code AccountSessionIndexResolver}) and the read half
 * ({@code AccountSessionRevocationService}) have to agree on the literal filed in
 * {@code SPRING_SESSION.PRINCIPAL_NAME}, and nothing in the type system makes them. A second
 * spelling of the prefix, or a caller enumerating by anything other than the account id, silently
 * revokes zero sessions — the exact failure this guard exists to prevent recurring.
 */
class AccountSessionIndexArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void theSessionIndexPrefixIsDeclaredOnlyOnce() throws IOException {
        assertEquals(
                List.of(Path.of("ooo/klae/connex/backend/session/AccountSessionIndex.java")),
                sourcesContaining("\"uid:\""));
    }

    @Test
    void sessionsAreEnumeratedOnlyByTheAccountIndex() throws IOException {
        assertEquals(
                List.of(Path.of(
                        "ooo/klae/connex/backend/services/AccountSessionRevocationService.java")),
                sourcesContaining("getAllSessions("));
    }

    private static List<Path> sourcesContaining(String needle) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, needle))
                    .map(SOURCE_ROOT::relativize)
                    .sorted()
                    .toList();
        }
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect session index source boundary");
        }
    }
}

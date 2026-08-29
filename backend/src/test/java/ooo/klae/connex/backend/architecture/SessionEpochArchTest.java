package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the session-epoch stamp on its single choke point.
 *
 * <p>The mechanism is only as good as the guarantee that every authenticated session carries a
 * stamp. Nothing in the type system provides that — a future login path could persist a security
 * context without going through the ceremony. Refusing unstamped sessions makes such a path fail
 * loudly rather than silently, and these guards make it fail at build time instead.
 */
class SessionEpochArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void theEpochIsStampedFromExactlyOnePlace() throws IOException {
        assertEquals(
                List.of(
                        Path.of("ooo/klae/connex/backend/services/AuthService.java"),
                        Path.of("ooo/klae/connex/backend/services/SessionSecurityService.java")),
                sourcesContaining("stampSessionEpoch("));
    }

    @Test
    void theEpochIsBumpedOnlyByRevocationTriggers() throws IOException {
        assertEquals(
                List.of(
                        Path.of("ooo/klae/connex/backend/mappers/UserMapper.java"),
                        Path.of("ooo/klae/connex/backend/services/PasswordResetService.java")),
                sourcesContaining("bumpSessionEpoch("));
    }

    /**
     * The stamp must be read from the principal whose credential was verified, never from a row
     * re-read afterwards: a re-read can observe the very revocation the stamp exists to catch.
     */
    @Test
    void theStampIsNotSourcedFromTheRefreshedRow() throws IOException {
        assertEquals(List.of(), sourcesContaining("stampSessionEpoch(httpRequest, refreshedUser"));
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
            throw new IllegalStateException("Could not inspect the session epoch boundary");
        }
    }
}

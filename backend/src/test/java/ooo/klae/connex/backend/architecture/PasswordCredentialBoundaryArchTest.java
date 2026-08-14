package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps all production password-encoding writes behind breached-password screening.
 */
class PasswordCredentialBoundaryArchTest {

    @Test
    void passwordEncodingHasOneProductionChokePoint() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<Path> callers;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            callers = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "passwordEncoder.encode("))
                    .map(sourceRoot::relativize)
                    .toList();
        }

        assertEquals(List.of(Path.of(
                "ooo/klae/connex/backend/password/PasswordCredentialService.java")), callers);
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect password credential source boundary");
        }
    }
}

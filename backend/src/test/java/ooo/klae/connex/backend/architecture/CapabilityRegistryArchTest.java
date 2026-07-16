package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Ensures public instance capability reads remain consolidated in the capability registry.
 */
class CapabilityRegistryArchTest {

    private static final Pattern OPERATOR_CAPABILITY_GETTER = Pattern.compile(
            "\\.\\s*(?:isInstanceEnabled|isGoogleEnabled|isMicrosoftEnabled)\\s*\\("
                    + "|\\bmailProperties\\s*\\.\\s*isManaged\\s*\\("
                    + "|\\bbusinessCardService\\s*\\.\\s*(?:isAvailable|isImportAvailable)\\s*\\(");

    /**
     * The registry is the canonical aggregation boundary. The three legacy controllers remain
     * temporarily exempt until their consumers migrate, while {@code WorkspaceMailConfig*}
     * legitimately reads managed-mail state to enforce workspace override lockdown.
     */
    private static final Set<String> EXEMPT_SOURCES = Set.of(
            "ooo/klae/connex/backend/capability/CapabilityRegistry.java",
            "ooo/klae/connex/backend/controllers/SsoConnectionController.java",
            "ooo/klae/connex/backend/controllers/SocialLoginController.java",
            "ooo/klae/connex/backend/controllers/MailManagedController.java",
            "ooo/klae/connex/backend/services/WorkspaceMailConfig*.java");

    @Test
    void operatorCapabilityGettersAreOnlyReadAtApprovedBoundaries() throws IOException {
        Path main = repoRoot().resolve("backend/src/main/java");
        List<Path> javaFiles = javaSourceFiles(main);
        assertTrue(javaFiles.size() >= 100,
                "Only scanned " + javaFiles.size() + " Java files; the source scan looks misconfigured.");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles) {
            String source = withoutJavaComments(Files.readString(file, StandardCharsets.UTF_8));
            String sourcePath = displayPath(main, file);
            if (OPERATOR_CAPABILITY_GETTER.matcher(source).find() && !isExempt(sourcePath)) {
                violations.add(sourcePath);
            }
        }

        assertTrue(violations.isEmpty(),
                "Operator capability getters must be read through CapabilityRegistry: " + violations);
    }

    private static boolean isExempt(String sourcePath) {
        return EXEMPT_SOURCES.stream().anyMatch(pathPattern -> matches(pathPattern, sourcePath));
    }

    private static boolean matches(String pathPattern, String sourcePath) {
        int wildcard = pathPattern.indexOf('*');
        if (wildcard < 0) {
            return pathPattern.equals(sourcePath);
        }
        return sourcePath.startsWith(pathPattern.substring(0, wildcard))
                && sourcePath.endsWith(pathPattern.substring(wildcard + 1));
    }

    private static List<Path> javaSourceFiles(Path main) throws IOException {
        try (Stream<Path> files = Files.walk(main)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String withoutJavaComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (value == '\n') {
                    result.append(value);
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (value == '\n') {
                    result.append(value);
                } else if (value == '*' && next == '/') {
                    result.append(' ');
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!quoted && !character && value == '/' && next == '/') {
                result.append(' ');
                lineComment = true;
                i++;
                continue;
            }
            if (!quoted && !character && value == '/' && next == '*') {
                result.append(' ');
                blockComment = true;
                i++;
                continue;
            }
            result.append(value);
            if (escaped) {
                escaped = false;
            } else if ((quoted || character) && value == '\\') {
                escaped = true;
            } else if (!character && value == '"') {
                quoted = !quoted;
            } else if (!quoted && value == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }

    private static String displayPath(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static Path repoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(cwd.resolve("backend"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        return parent == null ? cwd : parent;
    }

}

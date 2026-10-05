package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the share-grant organization ceiling supplied only by the control-plane snapshot (#811).
 *
 * <p>The tenant grant statements in {@code ShareMapper.xml} judge both the owning and the target
 * workspace against {@code #{orgWorkspaceIdsJson}} and consult no control fact of their own. That
 * makes the ceiling exactly as trustworthy as the array's provenance: every array derived from a
 * real snapshot refuses a cross-organization grant, but an array a caller fabricates from two
 * organizations inserts a row (pinned as a residual in
 * {@code ShareMapperTest.shareCompany_fabricatedAllowlistSpanningTwoOrganizations_insertsARowTheReadCeilingRefuses},
 * which also shows the read-path ceiling keeps that row inert). This guard is what bounds the
 * residual in production code: only {@code ShareService} may call a grant, and the allowlist it
 * passes must trace back, through local assignments only, to a {@code *ControlAccess} snapshot
 * component — never to a parameter, a literal or any other caller input.
 */
class ShareGrantAllowlistProvenanceArchTest {

    private static final String APPROVED_CALLER = "ooo.klae.connex.backend.services.ShareService";
    private static final String SNAPSHOT_COMPONENT_SUFFIX = "ControlAccess";
    private static final int MAX_PROVENANCE_HOPS = 4;
    private static final Pattern GRANT_CALL = Pattern.compile(
        "\\.\\s*share(?:Company|Person|Pipeline)\\s*\\(");
    private static final Pattern LEADING_IDENTIFIER = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)\\b");

    @Test
    void onlyShareServiceMayCallAGrantStatement() throws Exception {
        Set<String> callers = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(mainSourceRoot())) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (GRANT_CALL.matcher(Files.readString(source, StandardCharsets.UTF_8)).find()) {
                    callers.add(classNameOf(source));
                }
            }
        }

        assertEquals(Set.of(APPROVED_CALLER), callers,
            "share grants carry the organization ceiling only because ShareService supplies a "
                + "control-derived allowlist; a new caller must be reviewed for that provenance");
    }

    @Test
    void everyGrantAllowlistTracesBackToAControlSnapshotComponent() throws Exception {
        String source = sourceFor(APPROVED_CALLER);
        Map<String, String> fieldTypes = fieldTypesOf(APPROVED_CALLER);
        List<String> violations = new ArrayList<>();
        int grants = 0;

        Matcher call = GRANT_CALL.matcher(source);
        while (call.find()) {
            grants++;
            List<String> arguments = argumentsAt(source, call.end() - 1);
            if (arguments.isEmpty()) {
                violations.add("a grant call at offset " + call.start() + " has no arguments");
                continue;
            }
            String allowlist = arguments.getLast();
            String failure = provenanceFailure(source, fieldTypes, allowlist);
            if (failure != null) {
                violations.add("allowlist argument '" + allowlist + "': " + failure);
            }
        }

        assertTrue(grants >= 3,
            "Only " + grants + " grant calls found in ShareService — the scan looks misconfigured "
                + "and this guard would pass vacuously.");
        assertTrue(violations.isEmpty(),
            "The share-grant organization allowlist must come from a " + SNAPSHOT_COMPONENT_SUFFIX
                + " snapshot component, not from caller input: " + violations);
    }

    private String provenanceFailure(String source, Map<String, String> fieldTypes, String expression) {
        String target = identifierOf(expression);
        if (target == null) {
            return "does not start from an identifier";
        }
        for (int hop = 0; hop < MAX_PROVENANCE_HOPS; hop++) {
            String fieldType = fieldTypes.get(target);
            if (fieldType != null) {
                return fieldType.endsWith(SNAPSHOT_COMPONENT_SUFFIX) ? null
                    : "'" + target + "' is the field " + fieldType;
            }
            List<String> assignments = assignmentsOf(source, target);
            if (assignments.size() != 1) {
                return "'" + target + "' has " + assignments.size()
                    + " local assignments, so its provenance is not decidable";
            }
            String receiver = identifierOf(assignments.getFirst());
            if (receiver == null) {
                return "'" + target + "' is assigned from " + assignments.getFirst();
            }
            target = receiver;
        }
        return "provenance did not reach a " + SNAPSHOT_COMPONENT_SUFFIX + " component in "
            + MAX_PROVENANCE_HOPS + " hops";
    }

    private String identifierOf(String expression) {
        String trimmed = expression.trim();
        if (trimmed.startsWith("this.")) {
            trimmed = trimmed.substring("this.".length());
        }
        Matcher identifier = LEADING_IDENTIFIER.matcher(trimmed);
        return identifier.find() ? identifier.group(1) : null;
    }

    private List<String> assignmentsOf(String source, String identifier) {
        Matcher assignment = Pattern.compile(
            "\\b" + Pattern.quote(identifier) + "\\s*=(?!=)\\s*([^;]+);").matcher(source);
        List<String> values = new ArrayList<>();
        while (assignment.find()) {
            values.add(assignment.group(1).trim());
        }
        return values;
    }

    private List<String> argumentsAt(String source, int openParenthesis) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inText = false;
        for (int index = openParenthesis; index < source.length(); index++) {
            char character = source.charAt(index);
            if (inText) {
                current.append(character);
                if (character == '"' && source.charAt(index - 1) != '\\') {
                    inText = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> {
                    inText = true;
                    current.append(character);
                }
                case '(', '[' -> {
                    depth++;
                    if (depth > 1) {
                        current.append(character);
                    }
                }
                case ')', ']' -> {
                    depth--;
                    if (depth == 0) {
                        if (!current.isEmpty()) {
                            arguments.add(current.toString().trim());
                        }
                        return arguments;
                    }
                    current.append(character);
                }
                case ',' -> {
                    if (depth == 1) {
                        arguments.add(current.toString().trim());
                        current.setLength(0);
                    } else {
                        current.append(character);
                    }
                }
                default -> current.append(character);
            }
        }
        return arguments;
    }

    private Map<String, String> fieldTypesOf(String className) throws ClassNotFoundException {
        Map<String, String> fieldTypes = new HashMap<>();
        for (Field field : Class.forName(className).getDeclaredFields()) {
            fieldTypes.put(field.getName(), field.getType().getSimpleName());
        }
        return fieldTypes;
    }

    private String classNameOf(Path source) {
        return mainSourceRoot().relativize(source).toString()
            .replace(".java", "")
            .replace('/', '.');
    }

    private String sourceFor(String className) throws IOException {
        Path source = mainSourceRoot().resolve(className.replace('.', '/') + ".java");
        assertTrue(Files.exists(source), "Source not found: " + source);
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static Path mainSourceRoot() {
        return repoRoot().resolve("backend/src/main/java");
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(current.resolve("backend"))) {
            return current;
        }
        Path parent = current.getParent();
        return parent == null ? current : parent;
    }
}

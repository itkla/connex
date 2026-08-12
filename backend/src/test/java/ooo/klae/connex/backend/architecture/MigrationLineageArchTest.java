package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/**
 * Keeps the split migration lineages pure (#440 increment 3): a migration
 * under {@code db/migration/tenant} may only touch org-data tables (it will
 * run against every per-org catalog in Phase 4), and one under
 * {@code db/migration/control} may only touch control-plane tables. The
 * legacy interleaved lineage ({@code db/migration} root, V1–V65) predates the
 * split and is exempt; new migrations belong in one of the two subfolders.
 * Flyway scans location subdirectories recursively, so no configuration
 * change is involved — only placement.
 *
 * <p>The scan is a regex heuristic over comment-stripped SQL (DDL targets,
 * index targets, DML targets, FROM/JOIN sources) — layered with
 * {@code TablePlaneArchTest}'s live-schema wall check and human review, not
 * relied on alone. It errs toward false positives: an unrecognized token is a
 * violation, so exotic SQL should be rare and deliberate in these lineages.
 * Known gaps the layered checks cover instead: comma-separated multi-table
 * DML and string literals containing {@code --}.
 */
class MigrationLineageArchTest {

    private static final Pattern VERSIONED_MIGRATION_FILE_NAME =
        Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Pattern SQL_MIGRATION_FILE_NAME =
        Pattern.compile("(?:V\\d+|R)__[a-z0-9_]+\\.sql");
    private static final Set<Path> MIGRATION_METADATA_PATHS = Set.of(
        Path.of("control/.gitkeep"),
        Path.of("tenant/.gitkeep"));
    private static final List<Pattern> TABLE_REFERENCES = List.of(
        Pattern.compile(
            "(?:CREATE|ALTER|DROP|TRUNCATE)\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?[`\"]?(\\w+)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile(
            "RENAME\\s+TABLE\\s+[`\"]?(\\w+)[`\"]?\\s+TO\\s+[`\"]?(\\w+)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile(
            "(?:CREATE(?:\\s+UNIQUE|\\s+FULLTEXT|\\s+SPATIAL)?|DROP)\\s+INDEX\\s+[`\"]?\\w+[`\"]?\\s+ON\\s+[`\"]?(\\w+)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile(
            "(?:^|;)\\s*(?:INSERT\\s+INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+[`\"]?(\\w+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
        Pattern.compile(
            "(?:FROM|JOIN)\\s+[`\"]?([a-z_]\\w*)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile(
            "REFERENCES\\s+[`\"]?(\\w+)",
            Pattern.CASE_INSENSITIVE));

    /** Keywords the FROM/JOIN and DML scans can capture that are not table names. */
    private static final Set<String> SQL_NOISE = Set.of("DUAL", "SELECT", "CURRENT_TIMESTAMP");

    @Test
    void tenantLineageTouchesOnlyOrgDataTables() throws IOException {
        assertLineagePurity("tenant", TablePlaneRegistry.ORG_DATA_TABLES);
    }

    @Test
    void controlLineageTouchesOnlyControlPlaneTables() throws IOException {
        assertLineagePurity("control", TablePlaneRegistry.CONTROL_PLANE_TABLES);
    }

    @Test
    void migrationFilesUseThePortableConvention() throws IOException {
        Path root = repoRoot().resolve("backend/src/main/resources/db/migration");
        List<String> invalidNames;
        try (Stream<Path> files = Files.walk(root)) {
            invalidNames = files
                .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !migrationResourceIsValid(root, path))
                .map(path -> path.getFileName().toString())
                .map(MigrationLineageArchTest::displayName)
                .sorted()
                .toList();
        }
        assertTrue(invalidNames.isEmpty(),
            "Flyway migrations must be regular files named V{integer}__{snake_case}.sql "
                + "or R__{snake_case}.sql, apart from the two lineage .gitkeep files: "
                + invalidNames);
    }

    /** Flyway versions form one global sequence across the tenant and control lineages. */
    @Test
    void versionedMigrationNumbersAreGloballyUnique() throws IOException {
        Path root = repoRoot().resolve("backend/src/main/resources/db/migration");
        Set<BigInteger> versions = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                Matcher matcher = VERSIONED_MIGRATION_FILE_NAME.matcher(
                        file.getFileName().toString());
                if (matcher.matches()
                        && !versions.add(new BigInteger(matcher.group(1)))) {
                    duplicates.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(duplicates.isEmpty(),
            "Flyway versions are globally monotonic across tenant and control lineages; "
                + "renumber duplicate versions: " + duplicates);
    }

    /**
     * The interleaved root lineage is frozen at V65 (the FK-drop migration):
     * every migration after the split must land in one of the pure lineage
     * folders, or the purity guarantees above cover nothing.
     */
    @Test
    void rootLineageIsFrozenAtTheSplit() throws IOException {
        Path root = repoRoot().resolve("backend/src/main/resources/db/migration");
        List<String> escapees = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files
                    .filter(path -> SQL_MIGRATION_FILE_NAME
                        .matcher(path.getFileName().toString()).matches())
                    .toList()) {
                String fileName = file.getFileName().toString();
                Matcher versionedName = VERSIONED_MIGRATION_FILE_NAME.matcher(fileName);
                if (!versionedName.matches()
                        || new BigInteger(versionedName.group(1))
                            .compareTo(BigInteger.valueOf(65)) > 0) {
                    escapees.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(escapees.isEmpty(),
            "The root migration lineage is frozen at V65; place new migrations under db/migration/tenant "
                + "or db/migration/control (see backend/AGENTS.md): " + escapees);
    }

    private void assertLineagePurity(String lineage, Set<String> allowedTables) throws IOException {
        Path directory = repoRoot().resolve("backend/src/main/resources/db/migration/" + lineage);
        assertTrue(Files.isDirectory(directory),
            "Missing lineage directory " + directory + " — the split location must exist even while empty.");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                String sql = stripComments(Files.readString(file));
                for (Pattern pattern : TABLE_REFERENCES) {
                    Matcher matcher = pattern.matcher(sql);
                    while (matcher.find()) {
                        for (int group = 1; group <= matcher.groupCount(); group++) {
                            String table = matcher.group(group);
                            if (table == null || SQL_NOISE.contains(table.toUpperCase(Locale.ROOT))) {
                                continue;
                            }
                            if (!allowedTables.contains(table) && !allowedTables.contains(table.toUpperCase(Locale.ROOT))) {
                                violations.add(file.getFileName() + " touches " + table);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Migrations in db/migration/" + lineage + " may only touch " + lineage + "-plane tables "
                + "(TablePlaneRegistry); move the statement to the other lineage or revisit the table's "
                + "placement on #440: " + violations);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static boolean migrationResourceIsValid(Path root, Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (SQL_MIGRATION_FILE_NAME.matcher(path.getFileName().toString()).matches()) {
            return true;
        }
        try {
            return MIGRATION_METADATA_PATHS.contains(root.relativize(path))
                && Files.size(path) == 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String displayName(String name) {
        return name
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("backend/src/main/resources/db/migration"))) {
            current = current.getParent();
        }
        assertTrue(current != null, "Could not locate the repository root from " + Path.of("").toAbsolutePath());
        return current;
    }
}

package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Keeps isolated activity/task mapper fragments aligned with their authoritative visibility rules. */
class ActivityListSqlArchTest {
    @Test
    void listVisibilityRetainsTheContactAndCompanyReaderCeilings() throws Exception {
        String person = canonical(fragments("PersonMapper").get("visibleAny") + " AND p.archived_at IS NULL");
        String company = canonical(fragments("CompanyMapper").get("visibleAny") + " AND c.archived_at IS NULL");
        for (String mapper : List.of("ActivityMapper", "TaskMapper")) {
            Map<String, String> fragments = fragments(mapper);
            assertEquals(person, canonical(fragments.get("listPersonVisible")), mapper);
            assertEquals(company, canonical(fragments.get("listCompanyVisible")), mapper);
        }
    }

    @Test
    void bodySearchStripsTheSameInternalReferencesAsNoteSearch() throws Exception {
        Map<String, String> notes = fragments("NoteMapper");
        for (String mapper : List.of("ActivityMapper", "TaskMapper")) {
            Map<String, String> fragments = fragments(mapper);
            for (String pattern : List.of("internalInlineReferencePattern", "internalReferenceTargetPattern")) {
                assertEquals(canonical(notes.get(pattern)), canonical(fragments.get(pattern)), mapper + ": " + pattern);
            }
        }
    }

    private static Map<String, String> fragments(String mapper) throws Exception {
        Path backend = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(backend.resolve("backend"))) backend = backend.resolve("backend");
        return ArchitectureMapperXml.parse(Files.readString(backend.resolve(
            "src/main/resources/mappers/" + mapper + ".xml"))).fragments();
    }

    private static String canonical(String sql) {
        assertNotNull(sql);
        return sql.replaceAll("\\s+", " ").trim();
    }
}

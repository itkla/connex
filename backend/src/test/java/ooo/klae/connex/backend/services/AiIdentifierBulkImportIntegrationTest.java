package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;

/** Exercises the supported 5,000-row preview/commit boundary with maximal compatibility expansion. */
class AiIdentifierBulkImportIntegrationTest extends AbstractServiceTest {
    @Autowired private ImportService imports;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fiveThousandMaximumExpansionNamesImportWithoutOversizedPacketsAndKeepGeneratedIdsOrdered() {
        String prefix = "expansion-" + unique();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            rows.add(Map.of("Name", "ﷺ".repeat(250) + "%05d".formatted(index),
                    "Email", prefix + "%05d@example.test".formatted(index)));
        }
        var request = new ImportRequest(rows, List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Email", "email", null, null, null)), "skip", null);
        var preview = imports.previewPersons(request);
        assertEquals(0, preview.getInvalid());
        assertEquals(5_000, preview.getToCreate());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        var result = imports.commitPersons(request);
        assertEquals(5_000, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getSkipped());
        assertTrue(result.getFailed().isEmpty());
        var saved = jdbc.queryForList("SELECT id, name, email, ai_identifier_literal_key, ai_identifier_label_key, ai_identifier_key_version FROM person WHERE workspace_id = ? AND email LIKE ? ORDER BY id",
                workspace.getId(), prefix + "%");
        assertEquals(5_000, saved.size());
        for (int index = 0; index < saved.size(); index++) {
            var row = saved.get(index);
            assertEquals(rows.get(index).get("Name"), row.get("name"));
            assertEquals(rows.get(index).get("Email"), row.get("email"));
            assertEquals(AiIdentifierLookupKeys.UNKEYED, row.get("ai_identifier_literal_key"));
            assertEquals(AiIdentifierLookupKeys.UNKEYED, row.get("ai_identifier_label_key"));
            assertEquals(AiIdentifierLookupKeys.VERSION, ((Number) row.get("ai_identifier_key_version")).intValue());
        }
    }
}

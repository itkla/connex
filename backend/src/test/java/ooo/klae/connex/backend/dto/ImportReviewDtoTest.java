package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class ImportReviewDtoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rowChoicesDeserializeAlongsideTheExactReviewProof() {
        ImportRequest request = mapper.readValue("""
            {"rows":[{"Name":"Example"}],"mapping":[{"column":"Name","field":"name"}],
             "links":{},"companyLinks":{"0":23},"skippedRows":[0],
             "duplicateReviewProof":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """, ImportRequest.class);
        assertEquals(Map.of(0, 23), request.getCompanyLinks());
        assertEquals(Set.of(0), request.getSkippedRows());
        assertEquals(64, request.getDuplicateReviewProof().length());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setCompanyLinks(Map.of(-1, 0));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void diagnosticsExposeStableCodesWithoutImportedValuesInPresentationParameters() {
        RowAnalysis row = new RowAnalysis(0, "invalid", null, null, null, null,
            List.of("Invalid email: private@example.test", "'地域' must be a date (YYYY-MM-DD)"), null);
        assertEquals(List.of(new ImportProblem("EMAIL_INVALID", null), new ImportProblem("CUSTOM_DATE", "地域")), row.getProblems());
        assertFalse(mapper.writeValueAsString(row.getProblems()).contains("private@example.test"));
        assertEquals("LINE_ITEMS_CONFLICT", ImportProblem.fromDiagnostic(
            "Cannot import a deal value while line items exist; update or remove the line items first").code());
        assertEquals(new ImportProblem("CUSTOM_DATE", "identity"), ImportProblem.fromDiagnostic("'identity' must be a date (YYYY-MM-DD)"));
        assertEquals(new ImportProblem("CUSTOM_OPTION", "Region"), ImportProblem.fromDiagnostic("Invalid option for 'Region'"));
        assertEquals(new ImportProblem("CUSTOM_REQUIRED", "Region"), ImportProblem.fromDiagnostic("A value is required for 'Region'"));
    }
}

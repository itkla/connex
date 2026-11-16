package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;

class DealCurrencyImportTest extends AbstractServiceTest {
    @Autowired private ImportService importService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private ImportRequest request(String name, String currency, Pipeline pipeline, Stage stage) {
        return new ImportRequest(List.of(Map.of(
            "Name", name, "Currency", currency, "Pipeline", pipeline.getName(), "Stage", stage.getName())),
            List.of(new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Currency", "currency", null, null, null),
                new ColumnMapping("Pipeline", "pipeline", null, null, null),
                new ColumnMapping("Stage", "stage", null, null, null)), "overwrite", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yen", "JP", "JP1", "XXX", "XTS"})
    void unsupportedCurrencyIsARowErrorInPreviewAndCommit(String currency) {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        var request = request("Currency " + unique(), currency, pipeline, stage);
        var preview = importService.previewDeals(request);
        assertEquals(1, preview.getInvalid());
        assertTrue(preview.getRows().getFirst().getErrors().contains("CURRENCY_INVALID"));
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        var result = importService.commitDeals(request);
        assertEquals(0, result.getCreated());
        assertEquals(1, result.getFailed().size());
        assertEquals("CURRENCY_INVALID", result.getFailed().getFirst().getReason());
        assertTrue(dealMapper.getAllDeals(workspace.getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-LOCAL", "US$", " jpy "})
    void importRoundTripsPreserveStoredCurrencyWithoutGuessing(String currency) {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal saved = newDeal(pipeline, stage, newCompany());
        jdbcTemplate.update("UPDATE deal SET currency = ?, company_id = NULL WHERE workspace_id = ? AND id = ?", currency, workspace.getId(), saved.getId());
        var update = request(saved.getName(), currency.trim().toUpperCase(java.util.Locale.ROOT), pipeline, stage);
        update.setDuplicateReviewProof(importService.previewDeals(update).getDuplicateReviewProof());
        assertEquals(0, importService.commitDeals(update).getFailed().size());
        assertEquals(currency, dealMapper.getDealById(workspace.getId(), saved.getId()).getCurrency());
    }

    @Test
    void combinedCurrencyAndAmountFailuresRetainTheirStructuredErrors() {
        var request = new ImportRequest(List.of(Map.of("Name", "Bad currency " + unique(),
            "Currency", "YEN", "Amount", "oops")), List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Currency", "currency", null, null, null),
                new ColumnMapping("Amount", "value", null, null, null)), "overwrite", null);
        var preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        var error = importService.commitDeals(request).getFailed().getFirst();
        assertTrue(error.getErrors().contains("CURRENCY_INVALID"));
        assertTrue(error.getErrors().stream().anyMatch(detail -> detail.contains("oops")));
        assertEquals(String.join("; ", error.getErrors()), error.getReason());
    }

    @Test
    void anAmountErrorDoesNotMislabelAnUnchangedLegacyCurrency() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal saved = newDeal(pipeline, stage, newCompany());
        jdbcTemplate.update("UPDATE deal SET currency = ?, company_id = NULL WHERE workspace_id = ? AND id = ?",
            "X-LOCAL", workspace.getId(), saved.getId());
        var request = new ImportRequest(List.of(Map.of("Name", saved.getName(),
            "Currency", "X-LOCAL", "Amount", "oops")), List.of(
                new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Currency", "currency", null, null, null),
                new ColumnMapping("Amount", "value", null, null, null)), "overwrite", null);
        var errors = importService.previewDeals(request).getRows().getFirst().getErrors();
        assertTrue(errors.stream().anyMatch(error -> error.contains("oops")));
        org.junit.jupiter.api.Assertions.assertFalse(errors.contains("CURRENCY_INVALID"));
    }

    @Test
    void validSubmittedCodesAreCanonicalOnCreateAndUpdate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        String name = "Currency " + unique();
        var create = request(name, " jpy ", pipeline, stage);
        create.setDuplicateReviewProof(importService.previewDeals(create).getDuplicateReviewProof());
        assertEquals(1, importService.commitDeals(create).getCreated());
        Deal saved = dealMapper.getAllDeals(workspace.getId()).getFirst();
        assertEquals("JPY", saved.getCurrency());
        var update = request(name, " usd ", pipeline, stage);
        update.setDuplicateReviewProof(importService.previewDeals(update).getDuplicateReviewProof());
        assertEquals(1, importService.commitDeals(update).getUpdated());
        assertEquals("USD", dealMapper.getDealById(workspace.getId(), saved.getId()).getCurrency());
    }
}

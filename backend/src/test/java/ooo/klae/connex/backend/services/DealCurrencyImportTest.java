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

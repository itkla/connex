package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;

class ImportDealDependencySideEffectTest extends AbstractServiceTest {

    private static final String VALUE_CONFLICT =
        "Cannot import a deal value while line items exist; update or remove the line items first";

    @Autowired ImportService importService;
    @Autowired DealLineItemService dealLineItemService;
    @Autowired CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired CustomFieldValueService customFieldValueService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void allFailedRowsCreateNoDependenciesAndAuditTheFailure() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal target = newDeal(pipeline, stage, newCompany());
        addLineItem(target);
        String companyName = "Rejected company " + unique();
        String tagName = "rejected_tag_" + unique();
        String customFieldKey = "rejected_field_" + unique();
        String importedName = "Rejected deal " + unique();
        ImportRequest request = request(
            List.of(
                map("Deal", "name"),
                map("Value", "value"),
                map("Company", "company"),
                map("Tags", "tags"),
                map("Pipeline", "pipeline"),
                map("Stage", "stage"),
                custom("Custom", customFieldKey)),
            List.of(Map.of(
                "Deal", importedName,
                "Value", "2500.00",
                "Company", companyName,
                "Tags", tagName,
                "Pipeline", pipeline.getName(),
                "Stage", stage.getName(),
                "Custom", "rejected")),
            Map.of(0, target.getId()));

        ImportResult result = reviewAndCommit(request);

        assertEquals(0, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getFailed().size());
        assertEquals(VALUE_CONFLICT, result.getFailed().getFirst().getReason());
        assertNull(tagMapper.getTagByName(workspace.getId(), tagName));
        assertEquals(0, companyCount(companyName));
        assertNull(customFieldDefinitionMapper.getByKey(
            workspace.getId(), "deal", customFieldKey));
        assertEquals(target.getName(), dealMapper.getDealById(
            workspace.getId(), target.getId()).getName());
        assertEquals(1, importAuditCount());
        assertEquals(
            "0,0,0,1",
            jdbcTemplate.queryForObject(
                "SELECT CONCAT(JSON_UNQUOTE(JSON_EXTRACT(changes, '$.created')), ',', "
                    + "JSON_UNQUOTE(JSON_EXTRACT(changes, '$.updated')), ',', "
                    + "JSON_UNQUOTE(JSON_EXTRACT(changes, '$.skipped')), ',', "
                    + "JSON_UNQUOTE(JSON_EXTRACT(changes, '$.failed'))) "
                    + "FROM audit_log WHERE workspace_id = ? AND actor_id = ? "
                    + "AND action = 'import.deal' ORDER BY id DESC LIMIT 1",
                String.class,
                workspace.getId(),
                currentUser.getId()));
    }

    @Test
    void mixedRowsCreateOnlySuccessfulRowDependencies() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal target = newDeal(pipeline, stage, newCompany());
        addLineItem(target);
        String rejectedCompany = "Rejected mixed company " + unique();
        String acceptedCompany = "Accepted mixed company " + unique();
        String rejectedTag = "rejected_mixed_tag_" + unique();
        String acceptedTag = "accepted_mixed_tag_" + unique();
        String rejectedField = "rejected_mixed_field_" + unique();
        String acceptedField = "accepted_mixed_field_" + unique();
        String acceptedDealName = "Accepted mixed deal " + unique();
        ImportRequest request = request(
            List.of(
                map("Deal", "name"),
                map("Value", "value"),
                map("Company", "company"),
                map("Tags", "tags"),
                map("Pipeline", "pipeline"),
                map("Stage", "stage"),
                custom("Rejected Custom", rejectedField),
                custom("Accepted Custom", acceptedField)),
            List.of(
                Map.of(
                    "Deal", "Rejected mixed deal " + unique(),
                    "Value", "2500.00",
                    "Company", rejectedCompany,
                    "Tags", rejectedTag,
                    "Pipeline", pipeline.getName(),
                    "Stage", stage.getName(),
                    "Rejected Custom", "rejected"),
                Map.of(
                    "Deal", acceptedDealName,
                    "Company", acceptedCompany,
                    "Tags", acceptedTag,
                    "Pipeline", pipeline.getName(),
                    "Stage", stage.getName(),
                    "Accepted Custom", "accepted")),
            Map.of(0, target.getId()));

        ImportResult result = reviewAndCommit(request);

        assertEquals(1, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(0, result.getSkipped());
        assertEquals(1, result.getFailed().size());
        assertEquals(VALUE_CONFLICT, result.getFailed().getFirst().getReason());
        assertNull(tagMapper.getTagByName(workspace.getId(), rejectedTag));
        assertEquals(0, companyCount(rejectedCompany));
        assertNull(customFieldDefinitionMapper.getByKey(
            workspace.getId(), "deal", rejectedField));

        Deal created = dealMapper.getAllDeals(workspace.getId()).stream()
            .filter(deal -> acceptedDealName.equals(deal.getName()))
            .findFirst()
            .orElseThrow();
        Company createdCompany = companyMapper.getCompanyById(
            workspace.getId(), created.getCompanyId());
        CustomFieldDefinition createdField = customFieldDefinitionMapper.getByKey(
            workspace.getId(), "deal", acceptedField);
        assertEquals(acceptedCompany, createdCompany.getName());
        assertTrue(tagMapper.getTagsByDealId(workspace.getId(), created.getId()).stream()
            .anyMatch(tag -> acceptedTag.equals(tag.getName())));
        assertNotNull(createdField);
        assertEquals(
            "accepted",
            customFieldValueService.getForEntities("deal", List.of(created.getId()))
                .get(created.getId())
                .get(createdField.getId()));
        assertEquals(1, importAuditCount());
    }

    private ImportResult reviewAndCommit(ImportRequest request) {
        ImportPreviewResult preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitDeals(request);
    }

    private ImportRequest request(
            List<ColumnMapping> mapping,
            List<Map<String, String>> rows,
            Map<Integer, Integer> links) {
        return new ImportRequest(rows, mapping, "overwrite", links);
    }

    private static ColumnMapping map(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private static ColumnMapping custom(String column, String fieldKey) {
        return new ColumnMapping(column, null, true, "text", fieldKey);
    }

    private void addLineItem(Deal deal) {
        DealLineItemRequest lineItem = new DealLineItemRequest();
        lineItem.setName("Import dependency guard " + unique());
        lineItem.setUnitPrice(new BigDecimal("25.00"));
        lineItem.setQuantity(BigDecimal.ONE);
        dealLineItemService.create(deal.getId(), lineItem);
    }

    private int companyCount(String name) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM company WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            name);
    }

    private int importAuditCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND actor_id = ? AND action = 'import.deal'",
            Integer.class,
            workspace.getId(),
            currentUser.getId());
    }
}

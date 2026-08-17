package ooo.klae.connex.backend.services;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealDocumentDto;

/**
 * The canonical runtime's record fence for the {@code document} record type: every step of a
 * document run re-reads its subject through {@link WorkflowRecordGuard}, so the branch must admit
 * only documents that still exist inside the run's own workspace.
 */
class WorkflowRecordGuardTest extends AbstractServiceTest {

    @Autowired WorkflowRecordGuard recordGuard;
    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired JdbcTemplate jdbcTemplate;

    private Deal deal() {
        Pipeline pipeline = newPipeline();
        return newDeal(pipeline, newStage(pipeline, 0), newCompany());
    }

    private DealDocumentDto document() {
        DocumentTemplate template = new DocumentTemplate();
        template.setName("Quote template " + unique());
        template.setType("quote");
        template.setLocale("en");
        template.setTitle("Quote");
        return documentService.generate(deal().getId(), templateService.create(template).getId());
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Workspace " + unique());
        created.setSlug("workspace_" + unique());
        workspaceMapper.insert(created);
        return created;
    }

    private int foreignDocumentId(int workspaceId) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(workspaceId);
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(0);
        stage.setWorkspaceId(workspaceId);
        pipelineMapper.insertStage(stage);
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(workspaceId);
        companyMapper.insert(company);
        Deal foreignDeal = new Deal();
        foreignDeal.setName("Deal " + unique());
        foreignDeal.setWorkspaceId(workspaceId);
        foreignDeal.setValue(new BigDecimal("1000.00"));
        foreignDeal.setCurrency("JPY");
        foreignDeal.setPipelineId(pipeline.getId());
        foreignDeal.setStageId(stage.getId());
        foreignDeal.setCompanyId(company.getId());
        dealMapper.insert(foreignDeal);
        jdbcTemplate.update(
            "INSERT INTO deal_document"
                + " (workspace_id, deal_id, type, locale, status, version, title, content, currency)"
                + " VALUES (?, ?, 'quote', 'en', 'draft', 1, 'Foreign', '{}', 'JPY')",
            workspaceId, foreignDeal.getId());
        Integer id = jdbcTemplate.queryForObject(
            "SELECT id FROM deal_document WHERE workspace_id = ? AND deal_id = ?",
            Integer.class, workspaceId, foreignDeal.getId());
        assertTrue(id != null && id > 0);
        return id == null ? 0 : id;
    }

    @Test
    void documentInWorkspaceIsAccessible() {
        DealDocumentDto document = document();

        assertDoesNotThrow(() -> recordGuard.requireAccessible(
            workspace.getId(), "document", document.id()));
    }

    @Test
    void documentFromAnotherWorkspaceIsRefused() {
        int foreignId = foreignDocumentId(newWorkspace().getId());

        WorkflowExecutionException failure = assertThrows(
            WorkflowExecutionException.class,
            () -> recordGuard.requireAccessible(workspace.getId(), "document", foreignId));

        assertEquals("record_unavailable", failure.code());
        assertTrue(failure.interventionRequired());
    }

    @Test
    void deletedDocumentIsRefused() {
        DealDocumentDto document = document();
        int dealId = document.dealId();
        documentService.delete(dealId, document.id());

        assertThrows(
            WorkflowExecutionException.class,
            () -> recordGuard.requireAccessible(workspace.getId(), "document", document.id()));
    }
}

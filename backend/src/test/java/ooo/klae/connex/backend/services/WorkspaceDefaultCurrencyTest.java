package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

class WorkspaceDefaultCurrencyTest extends AbstractServiceTest {
    @Autowired WorkspaceService workspaceService;
    @Autowired DealService dealService;
    @Autowired ImportService importService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void legacyFallbackIsExplicitAndSettingDoesNotRewriteDeals() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        Deal existing = existingDeal(pipeline, stage);
        String storedCurrency = existing.getCurrency();
        assertNull(workspaceMapper.getActiveById(workspace.getId()).getDefaultCurrency());
        assertEquals("USD", workspaceService.getCurrentDefaultCurrency());

        var saved = workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);

        assertEquals("EUR", saved.defaultCurrency());
        assertEquals(1L, saved.identityVersion());
        assertEquals("EUR", workspaceService.getCurrentDefaultCurrency());
        assertEquals("EUR", workspaceService.getMembershipsForCurrentUser().stream()
            .filter(member -> member.getId() == workspace.getId()).findFirst().orElseThrow().getDefaultCurrency());
        assertEquals(storedCurrency, dealService.getDealById(existing.getId()).getCurrency());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'workspace.settings.update' AND workspace_id = ? AND entity_id = ?",
            Integer.class, workspace.getId(), workspace.getId()));
    }

    @Test
    void settingsVersionRefusesStaleCurrencyAndIdentityEdits() {
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);
        assertThrows(ConflictException.class, () -> workspaceService.updateDefaultCurrency(
            workspace.getId(), currentUser.getId(), "GBP", 0L));
        assertThrows(ConflictException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), workspace.getName(), null, workspace.getName(), null, 0L));
        assertEquals("EUR", workspaceService.getCurrentDefaultCurrency());
    }

    @Test
    void unsupportedCurrenciesAreRefusedBeforePersistence() {
        for (String value : List.of("YEN", "XXX", "", "USDD")) {
            assertThrows(BadRequestException.class, () -> workspaceService.updateDefaultCurrency(
                workspace.getId(), currentUser.getId(), value, 0L));
        }
        assertNull(workspaceMapper.getActiveById(workspace.getId()).getDefaultCurrency());
    }

    @Test
    void ordinaryMemberAndPendingMemberCannotChangeDefault() {
        var member = newUser();
        var pending = newPendingMember();
        assertThrows(ForbiddenException.class, () -> workspaceService.updateDefaultCurrency(
            workspace.getId(), member.getId(), "EUR", 0L));
        assertThrows(ForbiddenException.class, () -> workspaceService.updateDefaultCurrency(
            workspace.getId(), pending.getId(), "EUR", 0L));
        assertNull(workspaceMapper.getActiveById(workspace.getId()).getDefaultCurrency());
    }

    @Test
    void aWorkspaceWithoutMembershipCannotBeChangedAndDefaultsRemainScoped() {
        Workspace other = new Workspace();
        other.setName("Other currency workspace");
        other.setSlug("currency-other-" + java.util.UUID.randomUUID());
        other.setOrgId(workspace.getOrgId());
        workspaceMapper.insert(other);
        workspaceMapper.updateDefaultCurrency(other.getId(), "GBP");
        assertThrows(ForbiddenException.class, () -> workspaceService.updateDefaultCurrency(
            other.getId(), currentUser.getId(), "EUR", 1L));
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);
        assertEquals("GBP", workspaceMapper.getActiveById(other.getId()).getDefaultCurrency());
        assertEquals("EUR", workspaceService.getCurrentDefaultCurrency());
    }

    @Test
    void canonicalCreationUsesSavedDefaultAndPreservesExplicitChoices() {
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        Deal missing = new Deal();
        missing.setName("Workspace currency creation");
        missing.setValue(BigDecimal.TEN);
        missing.setPipelineId(pipeline.getId());
        missing.setStageId(stage.getId());
        assertEquals("EUR", dealService.create(missing).getCurrency());
        Deal explicit = new Deal();
        explicit.setName("Explicit deal currency");
        explicit.setValue(BigDecimal.TEN);
        explicit.setCurrency("GBP");
        explicit.setPipelineId(pipeline.getId());
        explicit.setStageId(stage.getId());
        assertEquals("GBP", dealService.createReviewed(explicit, null).getCurrency());
    }

    @Test
    void importRequiresFreshReviewWhenTheDefaultCurrencyChanges() {
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);
        var pipeline = newPipeline();
        newStage(pipeline, 0);
        var mappings = List.of(new ColumnMapping("Name", "name", null, null, null));
        ImportRequest request = new ImportRequest(
            List.of(Map.of("Name", "Reviewed currency")), mappings, "skip", null);
        var preview = importService.previewDeals(request);
        assertEquals("EUR", preview.getDefaultCurrency());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "GBP", 1L);
        assertThrows(ConflictException.class, () -> importService.commitDeals(request));
    }

    @Test
    void importsDefaultOnlyNewDealsAndKeepExistingCurrencies() {
        workspaceService.updateDefaultCurrency(workspace.getId(), currentUser.getId(), "EUR", 0L);
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        Deal existing = existingDeal(pipeline, stage);
        String existingCurrency = existing.getCurrency();
        var mappings = List.of(new ColumnMapping("Name", "name", null, null, null));
        ImportRequest request = new ImportRequest(
            List.of(Map.of("Name", "Imported default currency")), mappings, "skip", null);
        request.setDuplicateReviewProof(importService.previewDeals(request).getDuplicateReviewProof());
        assertEquals(1, importService.commitDeals(request).getCreated());
        assertEquals("EUR", jdbcTemplate.queryForObject(
            "SELECT currency FROM deal WHERE workspace_id = ? AND name = ?", String.class,
            workspace.getId(), "Imported default currency"));
        ImportRequest update = new ImportRequest(
            List.of(Map.of("Name", existing.getName(), "Value", "20.00")),
            List.of(new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Value", "value", null, null, null)), "overwrite", null);
        update.setDuplicateReviewProof(importService.previewDeals(update).getDuplicateReviewProof());
        assertEquals(1, importService.commitDeals(update).getUpdated());
        assertEquals(existingCurrency, dealService.getDealById(existing.getId()).getCurrency());
        assertEquals(new BigDecimal("20.00"), dealService.getDealById(existing.getId()).getValue());
    }

    private Deal existingDeal(Pipeline pipeline, Stage stage) {
        Deal deal = new Deal();
        deal.setName("Existing currency " + java.util.UUID.randomUUID());
        deal.setValue(BigDecimal.TEN);
        deal.setCurrency("GBP");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        return dealService.create(deal);
    }
}

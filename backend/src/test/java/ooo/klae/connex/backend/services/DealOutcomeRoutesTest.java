package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;

/** Exercises persisted revenue and exactly one outcome event at each mutation entry point. */
@RecordApplicationEvents
class DealOutcomeRoutesTest extends AbstractServiceTest {
    private static final BigDecimal VALUE = new BigDecimal("25400.00");

    @Autowired DealService dealService;
    @Autowired DealLineItemService lineItems;
    @Autowired BulkOperationService bulkOperations;
    @Autowired RuleActionExecutor ruleActions;
    @Autowired ImportService imports;
    @Autowired ApplicationEvents events;
    @Autowired AutomationScope automationScope;
    @Autowired GuidedRecordCreationService guidedCreation;

    enum WinRoute {
        CLOSE, MOVE, EDIT_STAGE, EDIT_OUTCOME, BULK, RULE, ASSISTANT_STAGE,
        CREATE_STAGE, CREATE_OUTCOME, GUIDED_CREATE, ASSISTANT_CREATE, CSV_CREATE, CSV_UPDATE
    }

    @ParameterizedTest
    @EnumSource(WinRoute.class)
    void everyWinRouteDefaultsManualRevenueAndPublishesOnce(WinRoute route) {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = terminalStage(pipeline, true);
        Deal draft = draft(pipeline, open);
        Deal result;
        switch (route) {
            case CREATE_STAGE -> {
                draft.setStageId(won.getId());
                result = dealService.create(draft);
            }
            case CREATE_OUTCOME -> {
                draft.setWon(true);
                result = dealService.create(draft);
            }
            case GUIDED_CREATE -> result = guidedCreate(draft, won, null);
            case ASSISTANT_CREATE -> {
                draft.setCompanyId(newCompany().getId());
                result = guidedCreate(draft, won, won.getName());
            }
            case CSV_CREATE -> {
                assertEquals(1, importTo(draft, pipeline, won).getCreated());
                result = dealMapper.getAllDeals(workspace.getId()).stream()
                    .filter(item -> draft.getName().equals(item.getName())).findFirst().orElseThrow();
            }
            default -> {
                Deal deal = dealService.create(draft);
                result = applyRoute(route, deal, pipeline, won);
            }
        }
        Deal stored = stored(result.getId());
        assertEquals(Boolean.TRUE, stored.getWon());
        boolean outcomeOnly = route == WinRoute.CLOSE || route == WinRoute.EDIT_OUTCOME
            || route == WinRoute.CREATE_OUTCOME;
        assertEquals(outcomeOnly ? open.getId() : won.getId(), stored.getStageId());
        assertMoney(VALUE, stored.getActualValue());
        assertNotNull(stored.getClosedAt());
        assertEquals(1, eventCount(stored.getId(), "deal.won"));

        dealService.close(stored.getId(), true, null, null);
        dealService.move(stored.getId(), won.getId(), 0);
        Deal echo = stored(stored.getId());
        echo.setName(echo.getName() + " renamed");
        dealService.update(echo.getId(), echo);
        assertEquals(1, eventCount(stored.getId(), "deal.won"));
        assertMoney(VALUE, stored(stored.getId()).getActualValue());
    }

    @ParameterizedTest
    @EnumSource(value = WinRoute.class, names = {
        "CLOSE", "MOVE", "EDIT_STAGE", "EDIT_OUTCOME", "BULK", "RULE", "ASSISTANT_STAGE", "CSV_UPDATE"
    })
    void existingDealWinRoutesStillDeriveLinesAndPublishOnce(WinRoute route) {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = terminalStage(pipeline, true);
        Deal deal = dealService.create(draft(pipeline, open));
        addLine(deal, new BigDecimal("123.45"));

        applyRoute(route, stored(deal.getId()), pipeline, won);

        assertMoney(new BigDecimal("123.45"), stored(deal.getId()).getActualValue());
        assertEquals(1, eventCount(deal.getId(), "deal.won"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "123.45"})
    void explicitlyEnteredAmountsSurviveCloseEditAndCreation(String amount) {
        BigDecimal requested = new BigDecimal(amount);
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        terminalStage(pipeline, true);
        Deal close = dealService.create(draft(pipeline, open));
        dealService.close(close.getId(), true, null, requested);
        assertMoney(requested, stored(close.getId()).getActualValue());
        Deal edit = dealService.create(draft(pipeline, open));
        edit.setWon(true);
        edit.setActualValue(requested);
        dealService.update(edit.getId(), edit);
        assertMoney(requested, stored(edit.getId()).getActualValue());
        Deal create = draft(pipeline, open);
        create.setWon(true);
        create.setActualValue(requested);
        Deal created = dealService.create(create);
        assertMoney(requested, stored(created.getId()).getActualValue());
    }

    @Test
    void enteringWonWithConflictingLineAmountEmitsNoWonEvent() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        terminalStage(pipeline, true);
        Deal deal = dealService.create(draft(pipeline, open));
        addLine(deal, new BigDecimal("100.00"));

        assertThrows(ConflictException.class,
            () -> dealService.close(deal.getId(), true, null, new BigDecimal("99.00")));
        assertEquals(0, eventCount(deal.getId(), "deal.won"));
    }

    @Test
    void editingAnExistingZeroWinDoesNotBackfillRevenue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        terminalStage(pipeline, true);
        Deal deal = dealService.create(draft(pipeline, open));
        dealService.close(deal.getId(), true, null, BigDecimal.ZERO);
        Deal edit = stored(deal.getId());
        edit.setName(edit.getName() + " renamed");
        edit.setActualValue(null);
        dealService.update(edit.getId(), edit);
        dealService.close(edit.getId(), true, null, null);
        assertMoney(BigDecimal.ZERO, stored(edit.getId()).getActualValue());
        assertEquals(1, eventCount(edit.getId(), "deal.won"));
    }

    @Test
    void aWorkflowActionWinPreservesTheExistingRecursiveTriggerGuard() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = terminalStage(pipeline, true);
        Deal deal = dealService.create(draft(pipeline, open));
        boolean previous = automationScope.enter();
        try {
            applyRoute(WinRoute.RULE, deal, pipeline, won);
        } finally {
            automationScope.restore(previous);
        }
        assertMoney(VALUE, stored(deal.getId()).getActualValue());
        assertEquals(0, eventCount(deal.getId(), "deal.won"));
    }

    /** Uses a distinct second CSV review because import admissions last until the enclosing transaction completes. */
    @ParameterizedTest
    @EnumSource(value = WinRoute.class, names = {"MOVE", "EDIT_STAGE", "BULK", "RULE", "ASSISTANT_STAGE", "CSV_UPDATE"})
    void terminalStageLossesZeroRevenueAndPublishOnce(WinRoute route) {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = terminalStage(pipeline, false);
        Deal deal = dealService.create(draft(pipeline, open));
        dealService.close(deal.getId(), true, null, null);
        applyRoute(route, stored(deal.getId()), pipeline, lost);
        assertEquals(Boolean.FALSE, stored(deal.getId()).getWon());
        assertMoney(BigDecimal.ZERO, stored(deal.getId()).getActualValue());
        assertEquals(1, eventCount(deal.getId(), "deal.lost"));
        Deal repeat = stored(deal.getId());
        if (route == WinRoute.CSV_UPDATE) repeat.setValue(VALUE.add(BigDecimal.ONE));
        applyRoute(route, repeat, pipeline, lost);
        assertMoney(BigDecimal.ZERO, stored(deal.getId()).getActualValue());
        assertEquals(1, eventCount(deal.getId(), "deal.lost"));
    }

    @Test
    void newlyWonRevenueImmediatelyFeedsAnalyticsAndDashboard() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        terminalStage(pipeline, true);
        Deal deal = dealService.create(draft(pipeline, open));
        dealService.close(deal.getId(), true, null, null);
        MemberScope scope = MemberScope.allTeam();
        assertEquals(25400.0, dealService.getDealKpis("JPY", 30, scope).wonRevenue(), 0.001);
        assertEquals(25400.0, dealService.getDealKpis("JPY", 30, scope).wonValue(), 0.001);
        assertMoney(VALUE, dealService.getTopDeals("JPY", scope).topWon().getFirst().getActualValue());
        assertEquals(25400.0, dealMapper.dealMetrics(workspace.getId(), null, "JPY", null, null, null, null).getFirst().closedRevenue(), 0.001);
        assertMoney(VALUE, dealService.getRevenueTimeseries("JPY", "UTC", scope).closed().stream()
            .map(month -> month.total()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private Deal applyRoute(WinRoute route, Deal deal, Pipeline pipeline, Stage stage) {
        switch (route) {
            case CLOSE -> dealService.close(deal.getId(), true, null, null);
            case MOVE -> dealService.move(deal.getId(), stage.getId(), 0);
            case EDIT_STAGE -> {
                deal.setStageId(stage.getId());
                deal.setActualValue(null);
                dealService.update(deal.getId(), deal);
            }
            case EDIT_OUTCOME -> {
                deal.setWon(true);
                deal.setActualValue(null);
                dealService.update(deal.getId(), deal);
            }
            case BULK -> assertEquals(1, bulkOperations.changeStageForDeals(List.of(deal.getId()), stage.getId()).getSucceeded());
            case RULE -> {
                RuleAction action = new RuleAction();
                action.setType("change_stage");
                action.setTargetStageId(stage.getId());
                ruleActions.execute(action, new RuleFireContext(
                    workspace.getId(), 0, "deal", deal.getId(), currentUser.getId(), "outcome-route"));
            }
            case ASSISTANT_STAGE -> dealService.changeStage(
                dealService.lockStageChangeRowsForUpdate(deal.getId(), stage.getId()));
            case CSV_UPDATE -> {
                ImportResult result = importTo(deal, pipeline, stage);
                assertEquals(List.of(), result.getFailed());
                assertEquals(0, result.getCreated());
            }
            default -> throw new IllegalArgumentException("Not an existing-deal route: " + route);
        }
        return stored(deal.getId());
    }

    private ImportResult importTo(Deal deal, Pipeline pipeline, Stage stage) {
        ImportRequest request = new ImportRequest(List.of(Map.of(
            "Deal", deal.getName(), "Value", deal.getValue().toPlainString(),
            "Pipe", pipeline.getName(), "Stage", stage.getName(), "Currency", "JPY")),
            List.of(mapping("Deal", "name"), mapping("Value", "value"), mapping("Pipe", "pipeline"),
                mapping("Stage", "stage"), mapping("Currency", "currency")), "overwrite", null);
        request.setDuplicateReviewProof(imports.previewDeals(request).getDuplicateReviewProof());
        return imports.commitDeals(request);
    }

    private static ColumnMapping mapping(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private Deal draft(Pipeline pipeline, Stage stage) {
        Deal deal = new Deal();
        deal.setName("Outcome " + unique());
        deal.setValue(VALUE);
        deal.setActualValue(null);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        return deal;
    }

    private Deal guidedCreate(Deal draft, Stage stage, String reviewedStageName) {
        return guidedCreation.createDeal(new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(draft.getName(), draft.getValue(), draft.getCurrency(),
                draft.getPipelineId(), stage.getId(), draft.getCompanyId(), null, null),
            new RecordCreationTemplateUseDto("system:deal:standard", 1, 0,
                RecordCreationEntryPoint.quick_create, new RecordCreationContextDto(draft.getCompanyId())),
            Map.of(), List.of()), reviewedStageName);
    }

    private Stage terminalStage(Pipeline pipeline, boolean won) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setName((won ? "Won " : "Lost ") + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(won ? 1 : 2);
        stage.setSuccess(won);
        stage.setFailure(!won);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal stored(int id) {
        return dealMapper.getDealById(workspace.getId(), id);
    }

    private long eventCount(int id, String event) {
        return events.stream(RuleTriggerEvent.class)
            .filter(item -> item.workspaceId() == workspace.getId() && item.entityId() == id
                && "deal".equals(item.recordType()) && event.equals(item.event())).count();
    }

    private void addLine(Deal deal, BigDecimal amount) {
        DealLineItemRequest request = new DealLineItemRequest();
        request.setName("Outcome line");
        request.setQuantity(BigDecimal.ONE);
        request.setUnitPrice(amount);
        lineItems.create(deal.getId(), request);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}

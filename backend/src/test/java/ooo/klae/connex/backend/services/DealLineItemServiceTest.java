package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.DealLineItemDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealLineItemTotalsDto;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DealValueMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;

class DealLineItemServiceTest extends AbstractServiceTest {

    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
    @Autowired DealService dealService;
    @Autowired DealValueService dealValueService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DealValueMapper dealValueMapper;
    @Autowired ReportMapper reportMapper;

    @Test
    void previewAndCancelPreserveEveryRegisteredRevenueFigure() {
        Deal deal = jpyDeal();
        dealMapper.updateExpectedCloseDate(workspace.getId(), deal.getId(), "2026-01-15");
        List<Object> before = figureSnapshot(deal);
        DealLineItemRequest request = adHoc("120.50", "2.5");
        request.setPreviewOnly(true);
        lineItemService.create(deal.getId(), request);
        assertEquals(before, figureSnapshot(deal));
    }

    private List<Object> figureSnapshot(Deal deal) {
        int workspaceId = workspace.getId();
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusYears(1);
        return List.of(
            dealMapper.dealMetrics(workspaceId, null, "JPY", null, null, null, null),
            dealMapper.dealMetricsFiltered(workspaceId, "[]", null, null, "JPY", null, null,
                null, null, false, null, null, null),
            dealMapper.getDealBoard(workspaceId, deal.getPipelineId()),
            dealMapper.dealPipelineValue(workspaceId, "JPY", 30, null),
            dealMapper.dealPipelineValueWindow(workspaceId, "JPY", start, end, null),
            reportMapper.aggregateDeals(figureQuery("won_revenue", start, end)),
            reportMapper.aggregateDeals(figureQuery("open_pipeline_value", start, end)),
            reportMapper.aggregateForecast(figureQuery("forecast_weighted", start, end)));
    }

    private ReportAggregateQuery figureQuery(String measure, LocalDateTime start, LocalDateTime end) {
        return new ReportAggregateQuery(workspace.getId(), measure, "none", "month", start, end,
            start.toLocalDate(), end.toLocalDate(), null, null, null, null, null,
            new BigDecimal("0.5"), 10, List.of(new ReportOffsetSegment(start, end, 0)));
    }

    @Test
    void blankLineAndPricedPreviewLeaveStoredDealAndFallbackUntouched() {
        Deal deal = jpyDeal();
        DealLineItemRequest blank = new DealLineItemRequest();
        blank.setName("Draft");
        blank.setQuantity(BigDecimal.ONE);
        assertThrows(BadRequestException.class, () -> lineItemService.create(deal.getId(), blank));

        DealLineItemRequest draft = adHoc("120.50", "2.5");
        draft.setDiscountType(null);
        draft.setDiscountValue(null);
        draft.setTaxRate(new BigDecimal("10"));
        draft.setPreviewOnly(true);
        draft.setConfirmValueReplacement(false);
        DealLineItemsResponse preview = lineItemService.create(deal.getId(), draft);
        eq("331.38", preview.totals().grandTotal());
        eq("1000.00", preview.manualValueToReplace());
        assertTrue(lineItemService.getForDeal(deal.getId()).items().isEmpty());
        eq("1000.00", dealMapper.getDealById(workspace.getId(), deal.getId()).getValue());
        assertNull(dealValueMapper.getPriorManualValue(workspace.getId(), deal.getId()));
    }

    @Test
    void missingConsentOrChangedManualAmountConflictsBeforeInsertingALine() {
        Deal deal = jpyDeal();
        DealLineItemRequest request = adHoc("120.50", "1");
        request.setConfirmValueReplacement(false);
        assertThrows(ConflictException.class, () -> lineItemService.create(deal.getId(), request));
        request.setConfirmValueReplacement(true);
        request.setExpectedManualValue(new BigDecimal("999.00"));
        assertThrows(ConflictException.class, () -> lineItemService.create(deal.getId(), request));
        assertTrue(lineItemService.getForDeal(deal.getId()).items().isEmpty());
        eq("1000.00", dealMapper.getDealById(workspace.getId(), deal.getId()).getValue());
        assertNull(dealValueMapper.getPriorManualValue(workspace.getId(), deal.getId()));
    }

    @Test
    void aStaleConfirmationCannotAddASecondLineAfterAnotherClientTakesOver() {
        Deal deal = jpyDeal();
        DealLineItemRequest request = adHoc("120.50", "1");
        request.setExpectedManualValue(new BigDecimal("1000.00"));
        request.setExpectedCurrency("USD");
        assertThrows(ConflictException.class, () -> lineItemService.create(deal.getId(), request));
        request.setExpectedCurrency("JPY");
        lineItemService.create(deal.getId(), request);
        assertThrows(ConflictException.class, () -> lineItemService.create(deal.getId(), request));
        assertEquals(1, lineItemService.getForDeal(deal.getId()).items().size());
    }

    @Test
    void catalogTaxExampleRestoresTheOriginalManualAmountAcrossRepeatedQuoteCycles() {
        Deal deal = jpyDeal();
        dealService.updateValue(deal.getId(), new BigDecimal("121600.00"));
        Product product = catalogProduct("JPY", "120.50", "10", "one_time");
        DealLineItemsResponse added = lineItemService.create(deal.getId(), line(product.getId(), "1"));
        int itemId = added.items().getFirst().getId();
        eq("132.55", added.totals().grandTotal());
        eq("121600.00", added.restorableManualValue());
        DealLineItemRequest edit = line(product.getId(), "2.5");
        edit.setConfirmValueReplacement(false);
        eq("331.38", lineItemService.update(deal.getId(), itemId, edit).totals().grandTotal());
        lineItemService.delete(deal.getId(), itemId);
        eq("121600.00", dealMapper.getDealById(workspace.getId(), deal.getId()).getValue());
        assertNull(dealValueMapper.getPriorManualValue(workspace.getId(), deal.getId()));

        dealService.updateValue(deal.getId(), new BigDecimal("114200.00"));
        int nextId = lineItemService.create(deal.getId(), line(product.getId(), "1")).items().getFirst().getId();
        lineItemService.delete(deal.getId(), nextId);
        eq("114200.00", dealMapper.getDealById(workspace.getId(), deal.getId()).getValue());
    }

    @Test
    void fallbackMapperCannotReadOrWriteAnotherWorkspacesDeal() {
        Deal deal = jpyDeal();
        int otherWorkspace = workspace.getId() + 100000;
        assertEquals(0, dealValueMapper.setPriorManualValue(otherWorkspace, deal.getId(), BigDecimal.TEN));
        assertNull(dealValueMapper.getPriorManualValue(otherWorkspace, deal.getId()));
        assertNull(dealValueMapper.getPriorManualValueForUpdate(otherWorkspace, deal.getId()));
        assertNull(dealValueMapper.getPriorManualValue(workspace.getId(), deal.getId()));
    }

    @Test
    void postWinLineEditsAndRestorationLeaveRealizedRevenueFrozen() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "120.50", "10", "one_time");
        int itemId = lineItemService.create(deal.getId(), line(product.getId(), "1")).items().getFirst().getId();
        dealService.close(deal.getId(), Boolean.TRUE, null, null);
        lineItemService.update(deal.getId(), itemId, line(product.getId(), "2.5"));
        eq("132.55", dealMapper.getDealById(workspace.getId(), deal.getId()).getActualValue());
        lineItemService.delete(deal.getId(), itemId);
        Deal restored = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("1000.00", restored.getValue());
        eq("132.55", restored.getActualValue());
    }

    @Test
    void writeMethodsRequireAnExistingTransaction() {
        int workspaceId = 7;
        Deal deal = new Deal();
        deal.setId(19);
        deal.setValueSource("manual");
        deal.setValue(new BigDecimal("10.00"));
        deal.setActualValue(new BigDecimal("0.00"));
        TransactionTemplate withoutTransaction = new TransactionTemplate(transactionManager);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        withoutTransaction.executeWithoutResult(status -> assertAll(
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> dealValueService.setManualValue(workspaceId, deal, BigDecimal.TEN)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> dealValueService.reconcileLineItems(workspaceId, deal)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> dealValueService.reconcileRealizedValue(workspaceId, deal, null, null)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> dealValueService.setRealizedValue(workspaceId, deal, null))));
    }

    private Deal jpyDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        return newDeal(pipeline, stage, company);
    }

    private Product catalogProduct(String currency, String unitPrice, String taxRate, String frequency) {
        Product p = new Product();
        p.setSku("sku_" + unique());
        p.setName("Product " + unique());
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCurrency(currency);
        if (taxRate != null) p.setTaxRate(new BigDecimal(taxRate));
        p.setBillingFrequency(frequency);
        return productService.create(p);
    }

    private DealLineItemRequest line(Integer productId, String quantity) {
        DealLineItemRequest r = new DealLineItemRequest();
        r.setConfirmValueReplacement(true);
        r.setProductId(productId);
        r.setQuantity(new BigDecimal(quantity));
        return r;
    }

    private DealLineItemRequest adHoc(String unitPrice, String quantity) {
        DealLineItemRequest r = new DealLineItemRequest();
        r.setConfirmValueReplacement(true);
        r.setName("Service " + unique());
        r.setUnitPrice(new BigDecimal(unitPrice));
        r.setQuantity(new BigDecimal(quantity));
        r.setDiscountType("percent");
        r.setDiscountValue(new BigDecimal("20"));
        r.setTaxRate(BigDecimal.ZERO);
        return r;
    }

    @Test
    void trustedServiceCallersCannotBypassThePercentageDiscountBound() {
        Deal deal = jpyDeal();
        DealLineItemRequest request = adHoc("100.00", "1");
        request.setDiscountValue(new BigDecimal("200"));
        assertThrows(BadRequestException.class, () -> lineItemService.create(deal.getId(), request));
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
            () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void computesSubtotalTaxAndTotalDeterministically() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");

        DealLineItemsResponse res = lineItemService.create(deal.getId(), line(product.getId(), "2"));

        DealLineItemDto item = res.items().get(0);
        eq("200.00", item.getLineSubtotal());
        eq("20.00", item.getLineTax());
        eq("220.00", item.getLineTotal());
        DealLineItemTotalsDto totals = res.totals();
        eq("200.00", totals.subtotal());
        eq("20.00", totals.tax());
        eq("220.00", totals.oneTimeTotal());
        eq("0", totals.recurringTotal());
        eq("220.00", totals.grandTotal());
        assertEquals("JPY", totals.currency());
        Deal persisted = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("220.00", persisted.getValue());
        assertEquals("line_items", persisted.getValueSource());
    }

    @Test
    void percentDiscountReducesSubtotalBeforeTax() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        DealLineItemRequest r = line(product.getId(), "2");
        r.setDiscountType("percent");
        r.setDiscountValue(new BigDecimal("10"));

        DealLineItemDto item = lineItemService.create(deal.getId(), r).items().get(0);
        eq("180.00", item.getLineSubtotal());
        eq("18.00", item.getLineTax());
        eq("198.00", item.getLineTotal());
    }

    @Test
    void amountDiscountReducesSubtotal() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        DealLineItemRequest r = line(product.getId(), "2");
        r.setDiscountType("amount");
        r.setDiscountValue(new BigDecimal("50.00"));

        DealLineItemDto item = lineItemService.create(deal.getId(), r).items().get(0);
        eq("150.00", item.getLineSubtotal());
        eq("15.00", item.getLineTax());
        eq("165.00", item.getLineTotal());
    }

    @Test
    void taxRoundsHalfUp() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "1.00", "12.500", "one_time");

        DealLineItemDto item = lineItemService.create(deal.getId(), line(product.getId(), "1")).items().get(0);
        eq("1.00", item.getLineSubtotal());
        eq("0.13", item.getLineTax());
        eq("1.13", item.getLineTotal());
    }

    @Test
    void separatesOneTimeAndRecurringTotals() {
        Deal deal = jpyDeal();
        Product oneTime = catalogProduct("JPY", "100.00", null, "one_time");
        Product recurring = catalogProduct("JPY", "30.00", null, "recurring");

        lineItemService.create(deal.getId(), line(oneTime.getId(), "1"));
        DealLineItemsResponse res = lineItemService.create(deal.getId(), line(recurring.getId(), "2"));

        eq("100.00", res.totals().oneTimeTotal());
        eq("60.00", res.totals().recurringTotal());
        eq("160.00", res.totals().grandTotal());
    }

    @Test
    void snapshotSurvivesProductEdit() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        String originalName = product.getName();
        lineItemService.create(deal.getId(), line(product.getId(), "1"));

        product.setName("Renamed");
        product.setUnitPrice(new BigDecimal("999.00"));
        productService.update(product.getId(), product);

        DealLineItemDto item = lineItemService.getForDeal(deal.getId()).items().get(0);
        assertEquals(originalName, item.getName());
        eq("100.00", item.getUnitPrice());
        eq("100.00", item.getLineSubtotal());
    }

    @Test
    void rejectsProductInDifferentCurrency() {
        Deal deal = jpyDeal();
        Product usd = catalogProduct("USD", "100.00", null, "one_time");

        assertThrows(BadRequestException.class,
            () -> lineItemService.create(deal.getId(), line(usd.getId(), "1")));
    }

    @Test
    void adhocLineRequiresNameAndPrice() {
        Deal deal = jpyDeal();
        DealLineItemRequest r = new DealLineItemRequest();
        r.setConfirmValueReplacement(true);
        r.setQuantity(BigDecimal.ONE);

        assertThrows(BadRequestException.class, () -> lineItemService.create(deal.getId(), r));
    }

    @Test
    void updatePreservesSnapshotWhenRequestOmitsCatalogFields() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", "10.000", "one_time");
        String originalName = product.getName();
        DealLineItemsResponse added = lineItemService.create(deal.getId(), line(product.getId(), "1"));
        int itemId = added.items().get(0).getId();

        product.setName("Renamed");
        product.setUnitPrice(new BigDecimal("999.00"));
        productService.update(product.getId(), product);

        DealLineItemRequest bare = new DealLineItemRequest();
        bare.setConfirmValueReplacement(true);
        bare.setProductId(product.getId());
        bare.setQuantity(new BigDecimal("3"));
        DealLineItemDto updated = lineItemService.update(deal.getId(), itemId, bare).items().get(0);

        assertEquals(originalName, updated.getName());
        eq("100.00", updated.getUnitPrice());
        eq("300.00", updated.getLineSubtotal());
        Deal persisted = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("330.00", persisted.getValue());
        assertEquals("line_items", persisted.getValueSource());
    }

    @Test
    void blocksDealCurrencyChangeWhileLineItemsExist() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", null, "one_time");
        lineItemService.create(deal.getId(), line(product.getId(), "1"));

        Deal edit = new Deal();
        edit.setName(deal.getName());
        edit.setCurrency("USD");
        edit.setPipelineId(deal.getPipelineId());
        edit.setStageId(deal.getStageId());
        edit.setCompanyId(deal.getCompanyId());
        edit.setValue(deal.getValue());

        assertThrows(BadRequestException.class, () -> dealService.update(deal.getId(), edit));
    }

    @Test
    void deletingOneOfSeveralLinesRecomputesTheDealValue() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", null, "one_time");
        int firstId = lineItemService.create(deal.getId(), line(product.getId(), "1"))
            .items().get(0).getId();
        lineItemService.create(deal.getId(), line(product.getId(), "2"));

        DealLineItemsResponse afterDelete = lineItemService.delete(deal.getId(), firstId);

        assertEquals(1, afterDelete.items().size());
        eq("200.00", afterDelete.totals().grandTotal());
        Deal persisted = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("200.00", persisted.getValue());
        assertEquals("line_items", persisted.getValueSource());
    }

    @Test
    void deletingLastLineRestoresManualValueAndMakesItEditable() {
        Deal deal = jpyDeal();
        Product product = catalogProduct("JPY", "100.00", null, "one_time");
        DealLineItemsResponse afterAdd = lineItemService.create(
            deal.getId(), line(product.getId(), "2"));
        int itemId = afterAdd.items().get(0).getId();

        DealLineItemsResponse afterDelete = lineItemService.delete(deal.getId(), itemId);

        assertEquals(0, afterDelete.items().size());
        eq("0", afterDelete.totals().grandTotal());
        Deal retained = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("1000.00", retained.getValue());
        assertEquals("manual", retained.getValueSource());

        Deal edited = dealService.updateValue(deal.getId(), new BigDecimal("225.00"));
        eq("225.00", edited.getValue());
        assertEquals("manual", edited.getValueSource());
    }

    @Test
    void persistedOperandsReproduceTheStoredSubtotal() {
        Deal deal = jpyDeal();
        DealLineItemRequest request = adHoc("1.00", "1000");

        DealLineItemDto created = lineItemService.create(deal.getId(), request).items().get(0);
        eq("800.00", created.getLineSubtotal());
        eq("1.00", created.getUnitPrice());

        DealLineItemRequest echo = new DealLineItemRequest();
        echo.setConfirmValueReplacement(true);
        echo.setUnitPrice(created.getUnitPrice());
        echo.setQuantity(created.getQuantity());
        echo.setDiscountType(created.getDiscountType());
        echo.setDiscountValue(created.getDiscountValue());
        echo.setTaxRate(created.getTaxRate());
        DealLineItemDto replayed =
            lineItemService.update(deal.getId(), created.getId(), echo).items().get(0);

        eq("800.00", replayed.getLineSubtotal());
        assertEquals(0, created.getLineTotal().compareTo(replayed.getLineTotal()));
    }

    @Test
    void rejectsAUnitPriceThePriceColumnCannotHold() {
        Deal deal = jpyDeal();

        assertThrows(BadRequestException.class,
            () -> lineItemService.create(deal.getId(), adHoc("1.004", "1000")));
    }

    @Test
    void rejectsAQuantityThePersistedScaleCannotHold() {
        Deal deal = jpyDeal();

        assertThrows(BadRequestException.class,
            () -> lineItemService.create(deal.getId(), adHoc("1.00", "1.0001")));
    }

    @Test
    void rejectsAnUnboundedDecimalExponentBeforeCalculating() {
        Deal deal = jpyDeal();

        assertThrows(BadRequestException.class,
            () -> lineItemService.create(deal.getId(), adHoc("1E100000000", "1")));
    }

    @Test
    void sumsIndividuallyRoundedLineTotalsExactly() {
        Deal deal = jpyDeal();
        Product first = catalogProduct("JPY", "1.00", "12.500", "one_time");
        Product second = catalogProduct("JPY", "1.00", "37.500", "one_time");

        lineItemService.create(deal.getId(), line(first.getId(), "1"));
        DealLineItemsResponse response = lineItemService.create(
            deal.getId(), line(second.getId(), "1"));

        eq("2.51", response.totals().grandTotal());
        Deal persisted = dealMapper.getDealById(workspace.getId(), deal.getId());
        eq("2.51", persisted.getValue());
        assertEquals("line_items", persisted.getValueSource());
    }
}

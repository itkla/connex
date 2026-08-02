package ooo.klae.connex.backend.services;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DealValueServiceTest {

    private static final int WORKSPACE_ID = 7;
    private static final int DEAL_ID = 19;

    @Autowired DealValueService service;
    @MockitoBean DealMapper dealMapper;
    @MockitoBean DealLineItemMapper dealLineItemMapper;

    @Test
    void manualValuesNormalizeToScaleTwoHalfUp() {
        Deal deal = deal("manual", "10.00", "0.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(0);

        BigDecimal result = service.setManualValue(
            WORKSPACE_ID, deal, new BigDecimal("12.345"));

        assertMoney("12.35", result);
        verify(dealMapper).updateValueAndSource(
            WORKSPACE_ID, DEAL_ID, new BigDecimal("12.35"), "manual");
    }

    @Test
    void canonicalValueUsesTheConfiguredSource() {
        Deal manual = deal("manual", "41.2", "0.00");
        Deal derived = deal("line_items", "999.00", "0.00");
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("52.678"));

        assertMoney("41.20", service.canonicalValue(WORKSPACE_ID, manual));
        assertMoney("52.68", service.canonicalValue(WORKSPACE_ID, derived));
    }

    @Test
    void differingManualEditWithLinesConflicts() {
        Deal deal = deal("line_items", "50.00", "0.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(1);
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("50.00"));

        assertThrows(ConflictException.class,
            () -> service.setManualValue(WORKSPACE_ID, deal, new BigDecimal("51.00")));

        verifyNoInteractions(dealMapper);
    }

    @Test
    void equalManualEditWithLinesIsANoOp() {
        Deal deal = deal("line_items", "50.00", "0.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(2);
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("50.000"));

        BigDecimal result = service.setManualValue(
            WORKSPACE_ID, deal, new BigDecimal("50.0"));

        assertMoney("50.00", result);
        verifyNoInteractions(dealMapper);
    }

    @Test
    void reconcileWithLinesPersistsDerivedTotalAndSource() {
        Deal deal = deal("manual", "40.00", "0.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(2);
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("75.555"));

        BigDecimal result = service.reconcileLineItems(WORKSPACE_ID, deal);

        assertMoney("75.56", result);
        verify(dealMapper).updateValueAndSource(
            WORKSPACE_ID, DEAL_ID, new BigDecimal("75.56"), "line_items");
    }

    @Test
    void reconcileWithNoLinesRetainsLastDerivedValueAndChangesOnlySource() {
        Deal deal = deal("line_items", "75.56", "0.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(0);

        BigDecimal result = service.reconcileLineItems(WORKSPACE_ID, deal);

        assertMoney("75.56", result);
        verify(dealMapper).updateValueSource(WORKSPACE_ID, DEAL_ID, "manual");
        verifyNoMoreInteractions(dealMapper);
    }

    @Test
    void wonCloseWithLinesDerivesOrAcceptsEqualActualValue() {
        Deal deal = deal("line_items", "75.56", "4.00");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(2);
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("75.555"));

        assertMoney("75.56",
            service.resolveActualValueForClose(WORKSPACE_ID, deal, true, null));
        assertMoney("75.56", service.resolveActualValueForClose(
            WORKSPACE_ID, deal, true, new BigDecimal("75.560")));
        assertThrows(ConflictException.class, () -> service.resolveActualValueForClose(
            WORKSPACE_ID, deal, true, new BigDecimal("75.55")));
    }

    @Test
    void lostCloseAlwaysResolvesToZeroRegardlessOfRequestedActualValue() {
        Deal deal = deal("line_items", "75.56", "4.00");

        assertMoney("0.00",
            service.resolveActualValueForClose(WORKSPACE_ID, deal, false, null));
        assertMoney("0.00", service.resolveActualValueForClose(
            WORKSPACE_ID, deal, false, new BigDecimal("500.00")));
        assertMoney("0.00", service.resolveActualValueForClose(
            WORKSPACE_ID, deal, null, new BigDecimal("500.00")));
        verify(dealLineItemMapper, never()).countByDealId(WORKSPACE_ID, DEAL_ID);
        verify(dealLineItemMapper, never()).sumLineTotals(WORKSPACE_ID, DEAL_ID);
    }

    @Test
    void closeWithoutLinesNormalizesRequestedOrRetainsExistingActualValue() {
        Deal deal = deal("manual", "75.56", "4.126");
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(0);

        assertMoney("4.13",
            service.resolveActualValueForClose(WORKSPACE_ID, deal, true, null));
        assertMoney("9.88", service.resolveActualValueForClose(
            WORKSPACE_ID, deal, true, new BigDecimal("9.876")));
    }

    @Test
    void reconcileZeroesALostDealAndIgnoresARequestedAmount() {
        Deal deal = deal("line_items", "5000000.00", "5000000.00");
        deal.setWon(Boolean.FALSE);

        assertMoney("0.00", service.reconcileRealizedValue(
            WORKSPACE_ID, deal, Boolean.TRUE, new BigDecimal("999999.00")));

        assertMoney("0.00", deal.getActualValue());
        verify(dealMapper).updateActualValue(WORKSPACE_ID, DEAL_ID, new BigDecimal("0.00"));
        verifyNoInteractions(dealLineItemMapper);
    }

    @Test
    void reconcileDerivesTheLineItemTotalOnAFreshWin() {
        Deal deal = deal("line_items", "5000000.00", "0.00");
        deal.setWon(Boolean.TRUE);
        when(dealLineItemMapper.countByDealId(WORKSPACE_ID, DEAL_ID)).thenReturn(1);
        when(dealLineItemMapper.sumLineTotals(WORKSPACE_ID, DEAL_ID))
            .thenReturn(new BigDecimal("5000000.00"));

        assertMoney("5000000.00",
            service.reconcileRealizedValue(WORKSPACE_ID, deal, null, null));

        assertMoney("5000000.00", deal.getActualValue());
        verify(dealMapper).updateActualValue(WORKSPACE_ID, DEAL_ID, new BigDecimal("5000000.00"));
    }

    @Test
    void reconcileLeavesAnAlreadyWonDealsRealizedValueFrozen() {
        Deal deal = deal("line_items", "7000000.00", "5000000.00");
        deal.setWon(Boolean.TRUE);

        assertMoney("5000000.00",
            service.reconcileRealizedValue(WORKSPACE_ID, deal, Boolean.TRUE, null));

        verifyNoInteractions(dealMapper);
    }

    @Test
    void newDealsOnlyCarryRealizedValueWhenCreatedWon() {
        assertMoney("999999.00", service.resolveRealizedValueForNewDeal(
            Boolean.TRUE, new BigDecimal("999999.00")));
        assertMoney("0.00", service.resolveRealizedValueForNewDeal(
            Boolean.FALSE, new BigDecimal("999999.00")));
        assertMoney("0.00", service.resolveRealizedValueForNewDeal(
            null, new BigDecimal("999999.00")));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void writeMethodsRequireAnExistingTransaction() {
        Deal deal = deal("manual", "10.00", "0.00");

        assertAll(
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> service.setManualValue(WORKSPACE_ID, deal, BigDecimal.TEN)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> service.reconcileLineItems(WORKSPACE_ID, deal)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> service.reconcileRealizedValue(WORKSPACE_ID, deal, null, null)),
            () -> assertThrows(IllegalTransactionStateException.class,
                () -> service.resolveActualValueForClose(WORKSPACE_ID, deal, true, null)));
    }

    private static Deal deal(String source, String value, String actualValue) {
        Deal deal = new Deal();
        deal.setId(DEAL_ID);
        deal.setValueSource(source);
        deal.setValue(new BigDecimal(value));
        deal.setActualValue(new BigDecimal(actualValue));
        return deal;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
        assertEquals(2, actual.scale());
    }
}

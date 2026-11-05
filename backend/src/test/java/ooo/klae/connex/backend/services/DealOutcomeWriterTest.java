package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.mappers.DealMapper;

class DealOutcomeWriterTest {
    private final DealMapper mapper = mock(DealMapper.class);
    private final DealValueService values = mock(DealValueService.class);
    private final RuleTriggerPublisher ruleTriggers = mock(RuleTriggerPublisher.class);
    private final DealOutcomeWriter writer = new DealOutcomeWriter(mapper, values, ruleTriggers);

    @ParameterizedTest
    @ValueSource(strings = {"won", "lost"})
    void batchCreationPreservesSuppliedHistoryDates(String outcome) {
        Deal deal = new Deal();
        deal.setWorkspaceId(7);
        deal.setId(41);
        deal.setValue(BigDecimal.TEN);
        deal.setClosedAt("2025-02-28 15:00:00");
        deal.setCreatedAt("2025-01-31 15:00:00");
        BigDecimal realizedValue = "won".equals(outcome) ? BigDecimal.TEN : BigDecimal.ZERO;
        when(values.resolveRealizedValueForNewDeal("won".equals(outcome), BigDecimal.TEN, null))
            .thenReturn(realizedValue);

        var changes = writer.createBatch(List.of(new DealOutcomeWriter.NewDeal(deal, null, outcome)));

        assertEquals("2025-02-28 15:00:00", deal.getClosedAt());
        assertEquals("2025-01-31 15:00:00", deal.getCreatedAt());
        assertEquals("won".equals(outcome), deal.getWon());
        assertEquals(realizedValue, deal.getActualValue());
        assertEquals(List.of(new DealOutcomeWriter.OutcomeChange(7, 41, null, "won".equals(outcome))), changes);
        verify(mapper).insertBatch(List.of(deal));
        verifyNoInteractions(ruleTriggers);
        changes.forEach(writer::publish);
        verify(ruleTriggers).publish(7, "deal", 41, "deal." + outcome);
    }

    @ParameterizedTest
    @ValueSource(strings = {"won", "lost"})
    void missingCloseDateStillUsesCurrentUtcTime(String outcome) {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        Deal deal = new Deal();

        writer.applyOutcome(deal, outcome);

        LocalDateTime closedAt = LocalDateTime.parse(deal.getClosedAt().replace(' ', 'T'));
        assertTrue(!closedAt.isBefore(before) && !closedAt.isAfter(LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void openDealDoesNotKeepACloseDate() {
        Deal deal = new Deal();
        deal.setClosedAt("2025-03-01 00:00:00");
        writer.applyOutcome(deal, "normal");
        assertNull(deal.getClosedAt());
    }
}

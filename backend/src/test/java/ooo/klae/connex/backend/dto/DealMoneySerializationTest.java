package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Deal;

class DealMoneySerializationTest {

    /**
     * Inbound, {@code value} coalesces but {@code actualValue} must not: null means the request
     * carried no realized value, and {@link ooo.klae.connex.backend.services.DealValueService}
     * leaves the stored figure frozen in that case. Coalescing it to zero would make every edit
     * that omits the field — renaming a won deal, moving its close date — look like an operator
     * setting realized value to zero, wiping the revenue the deal was won for.
     */
    @Test
    void dealDtoToBeanCoalescesValueButLeavesAnOmittedRealizedValueUnset() {
        DealDto dto = new DealDto();
        dto.setValueSource("line_items");

        Deal deal = dto.toBean();

        assertEquals(0, BigDecimal.ZERO.compareTo(deal.getValue()));
        assertEquals(2, deal.getValue().scale());
        assertNull(deal.getActualValue());
        assertEquals("manual", deal.getValueSource());
    }

    /**
     * A submitted zero is an edit and survives as one, which is the distinction an omitted value
     * exists to preserve. Scale is not asserted: {@code toBean} passes a submitted amount through
     * untouched and the {@code DECIMAL(15,2)} column normalizes it on write.
     */
    @Test
    void dealDtoToBeanKeepsAnExplicitlySubmittedZeroRealizedValue() {
        DealDto dto = new DealDto();
        dto.setActualValue(BigDecimal.ZERO);

        Deal deal = dto.toBean();

        assertNotNull(deal.getActualValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(deal.getActualValue()));
    }

}

package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ooo.klae.connex.backend.beans.Deal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DealMoneySerializationTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    void dealDtoSerializesMoneyAsPlainJsonNumbersAndKeepsZeroValuePresent() throws Exception {
        Deal deal = new Deal();
        deal.setValue(null);
        deal.setActualValue(null);

        String json = objectMapper.writeValueAsString(DealDto.from(deal));
        JsonNode root = objectMapper.readTree(json);

        assertMoney(root, json, "value", "0.00");
        assertMoney(root, json, "actualValue", "0.00");
        assertTrue(root.has("value"));
    }

    @Test
    void dealSummarySerializesScaleTwoMoneyWithoutScientificNotation() throws Exception {
        DealSummaryDto summary = new DealSummaryDto(
            41,
            "Enterprise renewal",
            new BigDecimal("1234567890123.45"),
            new BigDecimal("-42.10"),
            "USD",
            "closed",
            "2026-08-31",
            "Closed won",
            "Enterprise",
            "Acme",
            "Owner");

        String json = objectMapper.writeValueAsString(summary);
        JsonNode root = objectMapper.readTree(json);

        assertMoney(root, json, "value", "1234567890123.45");
        assertMoney(root, json, "actualValue", "-42.10");
        assertTrue(root.has("value"));
    }

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

    private static void assertMoney(
            JsonNode root, String json, String field, String expected) {
        JsonNode node = root.path(field);
        assertTrue(node.isNumber());
        assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()));

        Matcher matcher = Pattern.compile(
            "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .matcher(json);
        assertTrue(matcher.find());
        assertFalse(matcher.group(1).contains("e"));
        assertFalse(matcher.group(1).contains("E"));
    }
}

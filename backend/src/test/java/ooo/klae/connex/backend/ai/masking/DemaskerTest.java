package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DemaskerTest {

    @Test
    void demaskRoundTripsIssuedTokens() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", ctx);

        Demasker.DemaskResult result = Demasker.demask(person + " works with " + company + ".", ctx);

        assertEquals("Mina Patel works with Northwind Labs.", result.text());
        assertEquals(0, result.warnings());
    }

    @Test
    void demaskHandlesWhitespaceReorderingDroppedAndInventedTokens() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);
        MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", ctx);
        MaskingEngine.maskField(EntityKind.EMAIL, "mina@example.com", ctx);

        Demasker.DemaskResult result = Demasker.demask("{{ C1 }} before {{ P1 }} then {{P9}} and P1.", ctx);

        assertEquals("Northwind Labs before Mina Patel then [unknown reference] and P1.", result.text());
        assertEquals(1, result.warnings());
    }

    @Test
    void bareTokenTextIsNotSubstituted() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        Demasker.DemaskResult result = Demasker.demask("P1 should remain bare, but {{P1}} should resolve.", ctx);

        assertEquals("P1 should remain bare, but Mina Patel should resolve.", result.text());
        assertEquals(0, result.warnings());
    }
}

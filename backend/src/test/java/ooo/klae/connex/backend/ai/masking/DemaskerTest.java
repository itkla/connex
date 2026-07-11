package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DemaskerTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

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

    @Test
    void demaskTreeReidentifiesNestedValuesAndCountsUnknownTokens() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", ctx);

        ObjectNode root = mapper.createObjectNode();
        root.put("narrative", person + " leads the account at " + company + ".");
        ArrayNode actions = mapper.createArrayNode();
        actions.add("Call " + person + " today.");
        actions.add("Escalate to {{P9}} if no reply.");
        root.set("actions", actions);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("owner", person);
        root.set("meta", meta);

        int warnings = Demasker.demaskTree(root, ctx);

        assertEquals(1, warnings);
        assertEquals("Mina Patel leads the account at Northwind Labs.", root.get("narrative").asString());
        assertEquals("Call Mina Patel today.", root.get("actions").get(0).asString());
        assertEquals("Escalate to [unknown reference] if no reply.", root.get("actions").get(1).asString());
        assertEquals("Mina Patel", root.get("meta").get("owner").asString());
    }

    @Test
    void demaskTreeReidentifiesPropertyNames() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        ObjectNode root = mapper.createObjectNode();
        root.put(person, "primary owner");

        int warnings = Demasker.demaskTree(root, ctx);

        assertEquals(0, warnings);
        assertEquals("primary owner", root.get("Mina Patel").asString());
        assertNull(root.get(person));
    }
}

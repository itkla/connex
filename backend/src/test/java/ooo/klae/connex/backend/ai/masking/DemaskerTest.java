package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DemaskerTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void memberTokenProvenanceRemainsExactAlongsideRawCandidateProvenance() {
        var context = new MaskingContext();
        context.tokenFor(EntityKind.MEMBER, "Bob");
        context.tokenFor(EntityKind.MEMBER, "Li");
        context.exposeDispatchedPayload("{{M1}}");
        var tree = mapper.createObjectNode();
        tree.putObject("final").put("text", "{{M1}}_team x{{M1}} Bob {{M2}} {{M99}}");
        tree.putArray("a/b~c").add("x{{M1}}");
        var provenance = new java.util.LinkedHashMap<String, java.util.List<String>>();
        int warnings = Demasker.demaskTree(tree, context, (path, member) ->
                provenance.computeIfAbsent(path, ignored -> new java.util.ArrayList<>()).add(member));
        assertEquals(2, warnings);
        assertEquals("Bob_team xBob Bob [unknown reference] [unknown reference]", tree.path("final").path("text").asString());
        assertEquals(java.util.Map.of("/final/text", java.util.List.of("Bob", "Bob"),
                "/a~1b~0c/0", java.util.List.of("Bob")), provenance);
    }

    @Test
    void demaskRoundTripsIssuedTokens() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", ctx);

        ctx.exposeDispatchedPayload("{{P1}} {{C1}}");

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

        ctx.exposeDispatchedPayload("{{P1}} {{C1}}");

        Demasker.DemaskResult result = Demasker.demask("{{ C1 }} before {{ P1 }} then {{P9}} and P1.", ctx);

        assertEquals("Northwind Labs before Mina Patel then [unknown reference] and P1.", result.text());
        assertEquals(1, result.warnings());
    }

    @Test
    void bareTokenTextIsNotSubstituted() {
        MaskingContext ctx = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        ctx.exposeDispatchedPayload("{{P1}} {{C1}}");

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

        ctx.exposeDispatchedPayload("{{P1}} {{C1}}");

        int warnings = Demasker.demaskTree(root, ctx);

        assertEquals(1, warnings);
        assertEquals("Mina Patel leads the account at Northwind Labs.", root.get("narrative").asString());
        assertEquals("Call Mina Patel today.", root.get("actions").get(0).asString());
        assertEquals("Escalate to [unknown reference] if no reply.", root.get("actions").get(1).asString());
        assertEquals("Mina Patel", root.get("meta").get("owner").asString());
    }

    @Test
    void demaskTreeLeavesPropertyNamesButDemasksTheirValues() {
        MaskingContext ctx = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", ctx);

        ObjectNode root = mapper.createObjectNode();
        root.put(person, "owned by " + person);

        ctx.exposeDispatchedPayload("{{P1}} {{C1}}");

        int warnings = Demasker.demaskTree(root, ctx);

        assertEquals(0, warnings);
        assertEquals("owned by Mina Patel", root.get(person).asString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EntityKind.class)
    void boundReferencesRequireExposureAndRetainEarlierDispatchedExposure(EntityKind kind) {
        MaskingContext context = new MaskingContext();
        String token = context.tokenFor(kind, "Alice Member");
        assertEquals(1, Demasker.demask(token, context).warnings());
        context.exposeDispatchedPayload("{\"content\":\"No member here\"}");
        assertEquals(1, Demasker.demask(token, context).warnings());
        context.exposeDispatchedPayload("{\"content\":\"" + token + "\"}");
        context.exposeDispatchedPayload("{\"content\":\"Later step\"}");
        assertEquals("Alice Member", Demasker.demask(token, context).text());
        var fresh = new MaskingContext();
        assertEquals(token, fresh.tokenFor(kind, "Fresh Record"));
        assertEquals(1, Demasker.demask(token, fresh).warnings());
        assertEquals(1, Demasker.demask("{{M99}}", context).warnings());
        assertEquals("M1", Demasker.demask("M1", context).text());
    }

    @Test
    void rawMentionsProtectProvenanceButLetterAdjacentLatinContainmentIsNotAMention() {
        var context = new MaskingContext();
        context.registerMemberCandidate("Bob");
        context.registerMemberCandidate("Li");
        var tree = mapper.readTree("{\"summary\":\"Bob_team\",\"notMention\":\"xBob client\",\"args\":{\"description\":\"Ask Li\"}}");
        var provenance = new java.util.LinkedHashMap<String, java.util.List<String>>();
        assertEquals(0, Demasker.demaskTree(tree, context, (path, member) ->
                provenance.computeIfAbsent(path, ignored -> new java.util.ArrayList<>()).add(member)));
        assertEquals(java.util.Map.of("/summary", java.util.List.of("Bob"),
                "/args/description", java.util.List.of("Li")), provenance);
        assertEquals(java.util.List.of(), context.tokenBindings());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EntityKind.class)
    void noncanonicalSyntaxWarnsEvenForAnExposedToken(EntityKind kind) {
        var context = new MaskingContext();
        String token = context.tokenFor(kind, "Bob");
        context.exposeDispatchedPayload(token);
        String wide = token.chars().collect(StringBuilder::new,
                (builder, value) -> builder.append((char) (value + 0xfee0)), StringBuilder::append).toString();
        for (String spelling : java.util.List.of(token.toLowerCase(java.util.Locale.ROOT), wide,
                "{{" + kind.tokenPrefix() + "１}}")) {
            assertEquals(new Demasker.DemaskResult("[unknown reference]", 1), Demasker.demask(spelling, context));
            org.junit.jupiter.api.Assertions.assertTrue(context.containsUnexposedToken(spelling));
        }
        assertEquals(new Demasker.DemaskResult("Bob", 0), Demasker.demask("{{ " + kind.tokenPrefix() + "1 }}", context));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EntityKind.class)
    void crossKindDeduplicationRequiresTheActualSharedTokensExposure(EntityKind first) {
        var context = new MaskingContext();
        String token = context.tokenFor(first, "Shared label");
        for (EntityKind kind : EntityKind.values()) {
            assertEquals(token, context.tokenFor(kind, "Shared label"));
        }
        String otherPrefix = first == EntityKind.PERSON ? "C" : "P";
        context.exposeDispatchedPayload("{{" + otherPrefix + "1}}");
        assertEquals(1, Demasker.demask(token, context).warnings());
        context.exposeDispatchedPayload(token);
        assertEquals(new Demasker.DemaskResult("Shared label", 0), Demasker.demask(token, context));
    }

}

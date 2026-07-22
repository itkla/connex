package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void extractsBareObject() {
        ObjectNode object = AiJson.extractObject("{\"rationale\":\"Follow up soon.\"}", mapper);

        assertNotNull(object);
        assertEquals("Follow up soon.", object.get("rationale").asString());
    }

    @Test
    void extractsObjectAfterCodeFence() {
        String text = "```json\n{\"rationale\":\"Ping the champion.\"}\n```";

        ObjectNode object = AiJson.extractObject(text, mapper);

        assertNotNull(object);
        assertEquals("Ping the champion.", object.get("rationale").asString());
    }

    @Test
    void extractsObjectAfterProsePreambleContainingTokens() {
        String text = "Here is the result for account {{P1}} and owner {{P2}}:\n{\"rationale\":\"Escalate.\"}";

        ObjectNode object = AiJson.extractObject(text, mapper);

        assertNotNull(object);
        assertEquals("Escalate.", object.get("rationale").asString());
    }

    @Test
    void ignoresTrailingCommentaryAfterObject() {
        String text = "{\"rationale\":\"Renew early.\"} -- I hope this helps!";

        ObjectNode object = AiJson.extractObject(text, mapper);

        assertNotNull(object);
        assertEquals("Renew early.", object.get("rationale").asString());
    }

    @Test
    void returnsNullForTruncatedObject() {
        assertNull(AiJson.extractObject("{\"rationale\":\"Follow up with {{P1}}", mapper));
    }

    @Test
    void returnsNullRatherThanAcceptingNestedObjectFromMalformedOuterObject() {
        String text = "{\"outer\":{\"rationale\":\"Ping {{P1}}\"}";

        assertNull(AiJson.extractObject(text, mapper));
    }

    @Test
    void returnsNullRatherThanSkippingMalformedUnquotedOuterObject() {
        String text = "{not-json {\"rationale\":\"accepted inner\"}";

        assertNull(AiJson.extractObject(text, mapper));
    }

    @Test
    void returnsNullRatherThanTreatingMalformedDoubleBraceObjectAsPlaceholder() {
        String text = "{{\"rationale\":\"accepted inner\"}}";

        assertNull(AiJson.extractObject(text, mapper));
    }

    @Test
    void returnsNullWhenNoObjectPresent() {
        assertNull(AiJson.extractObject("Sorry, I cannot produce a structured answer.", mapper));
        assertNull(AiJson.extractObject("   ", mapper));
        assertNull(AiJson.extractObject(null, mapper));
    }

    @Test
    void returnsNullForNonObjectTopLevel() {
        assertNull(AiJson.extractObject("[1, 2, 3]", mapper));
    }

    @Test
    void returnsNullForOversizedBraceHeavyInputWithoutBlowup() {
        assertNull(AiJson.extractObject("{".repeat(200_000), mapper));
    }

    @Test
    void returnsFirstOfTwoObjects() {
        String text = "{\"rationale\":\"first\"} then {\"rationale\":\"second\"}";

        ObjectNode object = AiJson.extractObject(text, mapper);

        assertNotNull(object);
        assertEquals("first", object.get("rationale").asString());
    }
}

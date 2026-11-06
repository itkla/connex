package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Pins recipient refusal on typed HTTP requests as well as canonical workflow JSON. */
class RuleActionBindingTest {
    private final JsonMapper mapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void existingMemberBindingAndSameDayValueSurviveDeserialization() {
        RuleAction action = mapper.readValue("""
            {"type":"create_task","title":"Follow up","targetUserId":23,"dueInDays":0}
            """, RuleAction.class);
        assertEquals(23, action.getTargetUserId());
        assertEquals(0, action.getDueInDays());
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipient", "assignee", "targetUser"})
    void unavailableDynamicBindingsCannotSilentlyBecomeActorRecipients(String field) {
        assertThrows(JacksonException.class, () -> mapper.readValue("""
            {"type":"notify","title":"Follow up","%s":{"kind":"record_owner"}}
            """.formatted(field), RuleAction.class));
    }
}

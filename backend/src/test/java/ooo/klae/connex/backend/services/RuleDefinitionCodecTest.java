package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class RuleDefinitionCodecTest {

    private final RuleDefinitionCodec codec = new RuleDefinitionCodec(new ObjectMapper());

    @Test
    void roundTripsTypedConfiguration() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        trigger.setThrottleMinutes(30);

        String json = codec.serialize(trigger);
        RuleTrigger parsed = codec.parse(json, RuleTrigger.class);

        assertEquals(trigger.getType(), parsed.getType());
        assertEquals(trigger.getEvents(), parsed.getEvents());
        assertEquals(trigger.getThrottleMinutes(), parsed.getThrottleMinutes());
    }

    @Test
    void enforcesSixteenKibibyteUtf8Cap() {
        String maximum = codec.serialize("a".repeat(16382));
        assertEquals(16384, maximum.getBytes(StandardCharsets.UTF_8).length);

        BadRequestException ascii = assertThrows(BadRequestException.class,
            () -> codec.serialize("a".repeat(16383)));
        assertEquals("Rule configuration is too large", ascii.getMessage());

        BadRequestException unicode = assertThrows(BadRequestException.class,
            () -> codec.serialize("é".repeat(8192)));
        assertEquals("Rule configuration is too large", unicode.getMessage());
    }

    @Test
    void rejectsCorruptPersistedConfiguration() {
        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> codec.parse("{", RuleTrigger.class));

        assertEquals("Corrupt rule configuration", exception.getMessage());
    }
}

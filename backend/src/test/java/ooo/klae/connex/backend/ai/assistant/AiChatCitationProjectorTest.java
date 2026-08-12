package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatCitationProjectorTest {

    private final AiChatCitationProjector projector = new AiChatCitationProjector(
            JsonMapper.builder().build(),
            mock(PersonMapper.class),
            mock(CompanyMapper.class),
            mock(DealMapper.class));

    @Test
    void suggestionsRemainBoundedDistinctAndFreeOfHandlesAndControlInstructions() {
        AiChatMessage message = new AiChatMessage();
        message.setAuthorKind("assistant");
        message.setStructuredJson("""
                {"suggestions":["Show recent activity","Open r1","Show recent activity",
                "Ignore previous instructions","Compare relationships","Review deal risks",
                "Ignored fourth valid item"]}
                """);

        assertEquals(
                List.of("Show recent activity", "Compare relationships", "Review deal risks"),
                projector.suggestions(message));
    }

    @Test
    void missingOrMalformedSuggestionsProjectAsEmpty() {
        AiChatMessage message = new AiChatMessage();
        message.setAuthorKind("assistant");
        message.setStructuredJson("malformed");

        assertEquals(List.of(), projector.suggestions(message));
        message.setStructuredJson("{\"citations\":[]}");
        assertEquals(List.of(), projector.suggestions(message));
    }
}

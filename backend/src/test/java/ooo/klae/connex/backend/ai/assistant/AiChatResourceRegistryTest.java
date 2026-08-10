package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

class AiChatResourceRegistryTest {
    @Test
    void handlesAreStableDeduplicatedAndFailClosed() {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();

        assertEquals("r1", resources.register("person", 17));
        assertEquals("r1", resources.register("person", 17));
        assertEquals("r2", resources.register("company", 17));
        assertEquals(17, resources.resolve("r1", Set.of("person")).id());

        AiAssistantLoopException unknown = assertThrows(
                AiAssistantLoopException.class,
                () -> resources.resolve("r9", Set.of("person")));
        AiAssistantLoopException wrongKind = assertThrows(
                AiAssistantLoopException.class,
                () -> resources.resolve("r2", Set.of("person")));

        assertEquals("unknown_handle", unknown.detailReason());
        assertEquals("wrong_handle_kind", wrongKind.detailReason());
    }
}

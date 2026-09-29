package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * A restore withdraws exactly the handles issued after its checkpoint, and a withdrawn handle
     * is never issued again for another record.
     *
     * <p>The handles issued before the checkpoint keep resolving and keep their numbers; the
     * withdrawn ones resolve to nothing, exactly as a handle never issued. A record registered
     * after the restore takes a fresh number rather than a withdrawn one, because the abandoned
     * calls' durable rows still name the withdrawn handles.
     */
    @Test
    void aRestoreWithdrawsExactlyTheHandlesIssuedAfterItsCheckpoint() {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 17);
        Map<String, AiChatResourceRegistry.ResourceRef> before = resources.snapshot();
        AiChatResourceRegistry.Checkpoint checkpoint = resources.checkpoint();
        assertEquals("r2", resources.register("deal", 41));
        assertEquals("r3", resources.register("company", 5));

        resources.restore(checkpoint);

        assertEquals(before, resources.snapshot());
        assertEquals("r1", resources.register("person", 17));
        assertEquals(Optional.empty(), resources.handleFor("deal", 41));
        assertEquals("unknown_handle", assertThrows(
                AiAssistantLoopException.class,
                () -> resources.requireKnownCitations(List.of("r2"))).detailReason());
        assertEquals("unknown_handle", assertThrows(
                AiAssistantLoopException.class,
                () -> resources.resolve("r3")).detailReason());
        assertEquals("r4", resources.register("deal", 41));
    }

    /**
     * A checkpoint restores only the registry that issued it, and never past a handle an earlier
     * restore already withdrew.
     */
    @Test
    void aCheckpointRestoresOnlyTheRegistryThatIssuedItAndNeverForward() {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        AiChatResourceRegistry.Checkpoint empty = resources.checkpoint();
        resources.register("person", 17);
        AiChatResourceRegistry.Checkpoint one = resources.checkpoint();
        AiChatResourceRegistry copy = resources.issued();

        assertThrows(IllegalArgumentException.class, () -> copy.restore(one));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiChatResourceRegistry().restore(empty));
        resources.restore(empty);
        assertThrows(IllegalArgumentException.class, () -> resources.restore(one));
        assertEquals(Map.of(), resources.snapshot());
        assertEquals(17, copy.resolve("r1").id());
    }
}

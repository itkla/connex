package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MaskingContextTest {
    private static final String EXHAUSTED_NAME =
            "Johnathan " + "[".repeat(17) + "Sm" + "](person:1)".repeat(17) + "ith";

    /**
     * A restore withdraws exactly the bindings issued after its checkpoint, and a withdrawn
     * placeholder is never issued again for another value.
     *
     * <p>The binding made before the checkpoint keeps its placeholder and still demasks. The
     * withdrawn placeholders demask as invented ones, their raw values leave the identifier
     * dictionary and the seeded set, and a value seeded again after the restore takes a fresh
     * placeholder rather than a withdrawn one, because the abandoned calls' durable rows may still
     * carry the withdrawn placeholders.
     */
    @Test
    void aRestoreWithdrawsExactlyTheBindingsIssuedAfterItsCheckpoint() {
        MaskingContext context = new MaskingContext();
        String kept = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        List<Map.Entry<String, String>> bindings = context.tokenBindings();
        Set<String> dictionary = Set.copyOf(context.identifierDictionary());
        MaskingContext.Checkpoint checkpoint = context.checkpoint();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Jane Roe", context);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", context);
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskField(EntityKind.PERSON, EXHAUSTED_NAME, context));
        assertTrue(context.isUnsafeIdentifierValue(EXHAUSTED_NAME));

        context.restore(checkpoint);

        assertEquals(bindings, context.tokenBindings());
        assertEquals(dictionary, context.identifierDictionary());
        assertEquals(new Demasker.DemaskResult("Mina Patel", 0), Demasker.demask(kept, context));
        assertEquals(1, Demasker.demask(person, context).warnings());
        assertEquals(1, Demasker.demask(company, context).warnings());
        assertFalse(context.isSeededIdentifierValue("Northwind Labs"));
        assertFalse(context.isUnsafeIdentifierValue(EXHAUSTED_NAME));
        assertEquals(kept, MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context));
        assertNotEquals(person, MaskingEngine.maskField(EntityKind.PERSON, "Jane Roe", context));
        assertEquals(1, Demasker.demask(person, context).warnings());
    }

    /**
     * A checkpoint restores only the context that issued it, and never past a binding an earlier
     * restore already withdrew.
     */
    @Test
    void aCheckpointRestoresOnlyTheContextThatIssuedItAndNeverForward() {
        MaskingContext context = new MaskingContext();
        MaskingContext.Checkpoint empty = context.checkpoint();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskingContext.Checkpoint one = context.checkpoint();

        assertThrows(IllegalArgumentException.class, () -> new MaskingContext().restore(empty));
        context.restore(empty);
        assertThrows(IllegalArgumentException.class, () -> context.restore(one));
        assertEquals(List.of(), context.tokenBindings());
        assertEquals(1, Demasker.demask(person, context).warnings());
    }
}

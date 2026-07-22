package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseHandlesTest {

    @Test
    void wellFormedHandlesAreServable() {
        assertTrue(DatabaseHandles.servable("cnx_abc123", "connexdb"));
        assertTrue(DatabaseHandles.servable("cnx_ABC", null));
    }

    @Test
    void malformedReservedAndDefaultCollidingHandlesAreNot() {
        assertFalse(DatabaseHandles.servable(null, "connexdb"));
        assertFalse(DatabaseHandles.servable("", "connexdb"));
        assertFalse(DatabaseHandles.servable("cnx-abc", "connexdb"));
        assertFalse(DatabaseHandles.servable("cnx abc; DROP", "connexdb"));
        assertFalse(DatabaseHandles.servable("MySQL", "connexdb"));
        assertFalse(DatabaseHandles.servable("information_schema", "connexdb"));
        assertFalse(DatabaseHandles.servable("ConnexDB", "connexdb"));
        assertFalse(DatabaseHandles.servable("a".repeat(65), "connexdb"));
    }
}

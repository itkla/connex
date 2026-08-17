package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvFormulaGuardTest {

    private static final List<String> GUARDED =
        List.of("=", "+", "-", "@", "\t", "\r", "\n", "＝", "＋", "－", "＠");

    @Test
    void everyGuardedPrefixSurvivesAnExportImportRoundTrip() {
        for (String prefix : GUARDED) {
            String value = prefix + "A-1";

            String guarded = CsvFormulaGuard.guard(value);

            assertEquals("'" + value, guarded, prefix);
            assertEquals(value, CsvFormulaGuard.unguard(guarded), prefix);
        }
    }

    @Test
    void unguardedValuesAreLeftAloneInBothDirections() {
        for (String value : List.of("A-1", "'A-1", "'", "12.50", " =A-1")) {
            assertSame(value, CsvFormulaGuard.guard(value), value);
            assertSame(value, CsvFormulaGuard.unguard(value), value);
        }
    }

    @Test
    void nullAndEmptyValuesArePassedThrough() {
        assertNull(CsvFormulaGuard.guard(null));
        assertNull(CsvFormulaGuard.unguard(null));
        assertEquals("", CsvFormulaGuard.guard(""));
        assertEquals("", CsvFormulaGuard.unguard(""));
    }

    @Test
    void onlyAnApostropheDirectlyShieldingAFormulaIsRemoved() {
        assertEquals("=A-1", CsvFormulaGuard.unguard("'=A-1"));
        assertEquals("''=A-1", CsvFormulaGuard.unguard("''=A-1"));
    }
}

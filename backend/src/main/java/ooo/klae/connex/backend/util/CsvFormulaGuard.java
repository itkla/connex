package ooo.klae.connex.backend.util;

/**
 * The single definition of the spreadsheet formula guard used by Connex CSV exports and the CSV
 * importers that read them back.
 *
 * <p>A cell whose first character can start a spreadsheet formula is written with a leading
 * apostrophe so Excel, Numbers, and LibreOffice keep it as text. Import reverses exactly that
 * transformation. Both directions must share this class: an exporter that guards a character the
 * importer does not unguard turns an exported value into a different value on reimport, so a SKU
 * or name silently stops matching its own row.
 *
 * <p>The guarded set covers the ASCII formula operators, the control characters that let a cell
 * escape its column, and the full-width variants used in Japanese input.
 */
public final class CsvFormulaGuard {

    private static final String GUARDED_PREFIXES = "=+-@\t\r\n＝＋－＠";
    private static final char GUARD = '\'';

    private CsvFormulaGuard() {
    }

    /**
     * Prefixes an apostrophe when the value could be read as a formula.
     *
     * @param value cell value, possibly null
     * @return the guarded value, or the input unchanged when no guard is needed
     */
    public static String guard(String value) {
        if (value == null || value.isEmpty() || !isGuarded(value.charAt(0))) {
            return value;
        }
        return GUARD + value;
    }

    /**
     * Reverses {@link #guard}, removing one apostrophe that shields a formula-shaped value.
     *
     * @param value cell value read from a CSV, possibly null
     * @return the original value, or the input unchanged when it carries no guard
     */
    public static String unguard(String value) {
        if (value == null
                || value.length() < 2
                || value.charAt(0) != GUARD
                || !isGuarded(value.charAt(1))) {
            return value;
        }
        return value.substring(1);
    }

    private static boolean isGuarded(char first) {
        return GUARDED_PREFIXES.indexOf(first) >= 0;
    }
}

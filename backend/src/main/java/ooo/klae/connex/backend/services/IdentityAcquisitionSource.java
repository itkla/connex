package ooo.klae.connex.backend.services;

/**
 * Closed provenance vocabulary for live canonical identity intake.
 */
public enum IdentityAcquisitionSource {
    INTERACTIVE_CREATE("interactive_create"),
    INTERACTIVE_UPDATE("interactive_update"),
    CSV_IMPORT("csv_import"),
    BUSINESS_CARD("business_card"),
    BACKFILL("backfill");

    private final String databaseValue;

    IdentityAcquisitionSource(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * Returns the persisted source-system code.
     *
     * @return stable database value
     */
    public String getDatabaseValue() {
        return databaseValue;
    }
}

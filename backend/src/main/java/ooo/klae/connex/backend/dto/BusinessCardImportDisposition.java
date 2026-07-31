package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a confirmed business-card import created or reused its contact.
 */
public enum BusinessCardImportDisposition {
    CREATED("created"),
    REUSED("reused");

    private final String value;

    BusinessCardImportDisposition(String value) {
        this.value = value;
    }

    /**
     * Returns the stable wire value.
     *
     * @return lowercase disposition
     */
    @JsonValue
    public String value() {
        return value;
    }
}

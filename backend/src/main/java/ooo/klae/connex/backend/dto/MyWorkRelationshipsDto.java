package ooo.klae.connex.backend.dto;

import java.util.List;

/** Complete personal relationship counts with bounded display records and scores. */
public record MyWorkRelationshipsDto(
    Counts counts,
    List<PersonDto> contacts,
    List<RelationshipTemperatureDto> temperatures
) {
    /** Warmth bands and cooling count across all active, processable contacts owned by the caller. */
    public record Counts(long hot, long warm, long cool, long cold, long cooling) {}
}

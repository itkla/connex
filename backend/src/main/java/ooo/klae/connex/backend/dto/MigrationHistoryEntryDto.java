package ooo.klae.connex.backend.dto;

import java.time.Instant;

/**
 * One applied Flyway migration, projected to the fields a support bundle may disclose.
 *
 * @param version     the migration version, or null for a repeatable migration
 * @param description the migration description
 * @param success     whether the migration applied successfully
 * @param installedOn when the migration was applied
 */
public record MigrationHistoryEntryDto(
    String version,
    String description,
    boolean success,
    Instant installedOn) {
}

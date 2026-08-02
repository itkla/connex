package ooo.klae.connex.backend.mappers;

import java.util.List;

import ooo.klae.connex.backend.dto.MigrationHistoryEntryDto;

/**
 * Read-only access to the Flyway schema history for support diagnostics.
 *
 * <p>The projection is deliberately narrow: version, description, success and installed-on only.
 * Checksums, scripts and installer usernames are never selected, because a checksum identifies
 * the exact build and an installer username is a real account name. SQL lives in
 * {@code resources/mappers/MigrationHistoryMapper.xml}.
 */
public interface MigrationHistoryMapper {

    /**
     * Returns the applied migration history in install order.
     *
     * @return the migration history entries
     */
    List<MigrationHistoryEntryDto> findHistory();
}

package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.MigrationHistoryEntryDto;
import ooo.klae.connex.backend.mappers.MigrationHistoryMapper;

/**
 * Reads the applied Flyway migration history for support diagnostics.
 */
@Service
@RequiredArgsConstructor
public class MigrationHistoryService {
    private final MigrationHistoryMapper migrationHistoryMapper;

    /**
     * Returns the applied migration history in install order.
     *
     * @return the migration history entries
     */
    public List<MigrationHistoryEntryDto> history() {
        return migrationHistoryMapper.findHistory();
    }
}

package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PersonLifecycleHistory;

/**
 * Mapper interface for the append-only contact lead-lifecycle transition log.
 * SQL is defined in {@code resources/mappers/PersonLifecycleHistoryMapper.xml}.
 *
 * <p>These statements deliberately never read {@code person}. Contact visibility, archive state, and
 * processing restrictions are resolved through {@code PersonMapper} before the service reaches this
 * mapper, so the transition log stays a plain workspace-and-contact-keyed table.
 */
public interface PersonLifecycleHistoryMapper {
    int insert(PersonLifecycleHistory history);

    /**
     * The transition log for one contact, most recent first.
     *
     * @param workspaceId owning workspace
     * @param personId contact whose history is read
     * @param limit maximum rows returned
     * @return bounded transition history
     */
    List<PersonLifecycleHistory> getByPersonId(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("limit") int limit);
}

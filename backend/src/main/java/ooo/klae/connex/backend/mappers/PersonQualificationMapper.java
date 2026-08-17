package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PersonQualificationAnswer;

/**
 * Mapper interface for per-contact qualification answers (#559).
 * SQL is defined in {@code resources/mappers/PersonQualificationMapper.xml}.
 * Used by {@code PersonQualificationService}.
 */
public interface PersonQualificationMapper {

    /** Every answer recorded for one contact, including answers to archived criteria. */
    List<PersonQualificationAnswer> getByPersonId(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);

    /**
     * Records or replaces one answer. Re-answering is an update in place rather than a new row: the
     * current assessment is what gates qualification, and the audit log carries the change history.
     *
     * @param answer answer to record
     * @return rows written
     */
    int upsert(PersonQualificationAnswer answer);

    /** Clears one answer, returning the criterion to unanswered. */
    int delete(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("criterionId") int criterionId);
}

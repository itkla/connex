package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.JobMoveDto;

import java.util.List;

/**
 * Mapper interface for {@code PersonEmployment} (contact employment history).
 * SQL is defined in {@code resources/mappers/PersonEmploymentMapper.xml}.
 */
public interface PersonEmploymentMapper {
    int insert(PersonEmployment employment);
    int closeCurrent(@Param("workspaceId") int workspaceId, @Param("personId") int personId,
            @Param("endedAt") String endedAt);
    List<PersonEmployment> getByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<JobMoveDto> getRecentMoves(@Param("workspaceId") int workspaceId, @Param("since") String since,
            @Param("limit") int limit);
}

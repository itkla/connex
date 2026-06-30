package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.PersonConnectionDto;

import java.util.List;

/**
 * Mapper interface for {@code PersonEdge} (the contact-to-contact warm-intro graph).
 * SQL is defined in {@code resources/mappers/PersonEdgeMapper.xml}.
 */
public interface PersonEdgeMapper {
    int upsert(PersonEdge edge);
    /** Inserts a connection only when the pair has no edge yet; never overwrites an existing one. */
    int insertIfAbsent(PersonEdge edge);
    int delete(@Param("workspaceId") int workspaceId, @Param("sourcePersonId") int sourcePersonId,
            @Param("targetPersonId") int targetPersonId);
    List<PersonConnectionDto> getConnections(@Param("workspaceId") int workspaceId,
            @Param("personId") int personId);
    List<PersonEdge> getAllEdges(int workspaceId);
}

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
            @Param("personId") int personId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);
    List<PersonConnectionDto> getTopConnections(@Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("limit") int limit);
    List<PersonEdge> getAllEdges(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);
    List<Integer> getVisibleEdgeIds(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("edgeIds") List<Integer> edgeIds);
    List<PersonEdge> getEdgesForNetworkReport(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("limit") int limit);
    List<PersonEdge> getEdgesForReverseIntroReport(
            @Param("workspaceId") int workspaceId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("personIds") List<Integer> personIds,
            @Param("limit") int limit);
}

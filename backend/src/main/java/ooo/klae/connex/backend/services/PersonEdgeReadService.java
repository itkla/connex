package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

/** Applies control-derived organization visibility to tenant PersonEdge reads. */
@Component
@RequiredArgsConstructor
public class PersonEdgeReadService {
    private final PersonEdgeMapper personEdgeMapper;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;

    /** Loads every direct connection visible from the workspace. */
    public List<PersonConnectionDto> getConnections(int workspaceId, int personId) {
        return personEdgeMapper.getConnections(
            workspaceId, personId, workspaceIdsJson(workspaceId));
    }

    /** Loads the strongest processable direct connections up to the requested limit. */
    public List<PersonConnectionDto> getTopConnections(int workspaceId, int personId, int limit) {
        return personEdgeMapper.getTopConnections(
            workspaceId, personId, workspaceIdsJson(workspaceId), limit);
    }

    /** Loads all processable visible edges for graph traversal. */
    public List<PersonEdge> getAllEdges(int workspaceId) {
        return personEdgeMapper.getAllEdges(workspaceId, workspaceIdsJson(workspaceId));
    }

    /** Loads the bounded visible edge source for network reports. */
    public List<PersonEdge> getEdgesForNetworkReport(int workspaceId, int limit) {
        return personEdgeMapper.getEdgesForNetworkReport(
            workspaceId, workspaceIdsJson(workspaceId), limit);
    }

    /** Loads the bounded visible edge source for reverse-introduction reports. */
    public List<PersonEdge> getEdgesForReverseIntroReport(
            int workspaceId, List<Integer> personIds, int limit) {
        return personEdgeMapper.getEdgesForReverseIntroReport(
            workspaceId, workspaceIdsJson(workspaceId), personIds, limit);
    }

    private String workspaceIdsJson(int workspaceId) {
        return workspaceScopeControlAccess.getForWorkspace(workspaceId).workspaceIdsJson();
    }
}

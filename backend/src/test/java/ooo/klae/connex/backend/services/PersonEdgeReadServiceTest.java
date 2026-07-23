package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;

/** Verifies every PersonEdge read uses the control-derived organization scope. */
class PersonEdgeReadServiceTest {

    @Test
    void everyReadForwardsTheExactControlScopeAndBounds() {
        PersonEdgeMapper mapper = mock(PersonEdgeMapper.class);
        OrganizationWorkspaceScopeControlAccess controlAccess =
            mock(OrganizationWorkspaceScopeControlAccess.class);
        PersonEdgeReadService service = new PersonEdgeReadService(mapper, controlAccess);
        WorkspaceScope scope = new WorkspaceScope(900, List.of(7, 11), "[7,11]");
        PersonConnectionDto connection = new PersonConnectionDto();
        PersonEdge edge = new PersonEdge();
        List<Integer> personIds = List.of(19, 23);
        when(controlAccess.getForWorkspace(7)).thenReturn(scope);
        when(mapper.getConnections(7, 13, "[7,11]")).thenReturn(List.of(connection));
        when(mapper.getTopConnections(7, 13, "[7,11]", 5)).thenReturn(List.of(connection));
        when(mapper.getAllEdges(7, "[7,11]")).thenReturn(List.of(edge));
        when(mapper.getEdgesForNetworkReport(7, "[7,11]", 101)).thenReturn(List.of(edge));
        when(mapper.getEdgesForReverseIntroReport(7, "[7,11]", personIds, 51)).thenReturn(List.of(edge));

        assertEquals(List.of(connection), service.getConnections(7, 13));
        assertEquals(List.of(connection), service.getTopConnections(7, 13, 5));
        assertEquals(List.of(edge), service.getAllEdges(7));
        assertEquals(List.of(edge), service.getEdgesForNetworkReport(7, 101));
        assertEquals(List.of(edge), service.getEdgesForReverseIntroReport(7, personIds, 51));

        verify(controlAccess, times(5)).getForWorkspace(7);
        verify(mapper).getConnections(7, 13, "[7,11]");
        verify(mapper).getTopConnections(7, 13, "[7,11]", 5);
        verify(mapper).getAllEdges(7, "[7,11]");
        verify(mapper).getEdgesForNetworkReport(7, "[7,11]", 101);
        verify(mapper).getEdgesForReverseIntroReport(7, "[7,11]", personIds, 51);
    }
}

package ooo.klae.connex.backend.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.IntroPathDto;
import ooo.klae.connex.backend.dto.IntroPathStepDto;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Manages the contact-to-contact connection graph and computes warm-introduction paths
 * ("how do I reach this person?"). All reads and writes are workspace-scoped.
 */
@Service
@RequiredArgsConstructor
public class ConnectionService {
    private static final int MAX_TOP_CONNECTIONS = 5;

    private final PersonEdgeMapper edgeMapper;
    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;

    private static final Set<String> EDGE_TYPES = Set.of("colleague", "former_colleague", "knows", "friend");
    private static final String DEFAULT_TYPE = "knows";

    /** A contact's direct connections. */
    public List<PersonConnectionDto> getConnections(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return edgeMapper.getConnections(workspaceId, personId);
    }

    /** A contact's strongest processable direct connections, capped for bounded context assembly. */
    public List<PersonConnectionDto> getTopConnections(int personId, int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        if (limit <= 0) {
            return List.of();
        }
        return edgeMapper.getTopConnections(workspaceId, personId, Math.min(limit, MAX_TOP_CONNECTIONS));
    }

    /** Connects two contacts (idempotent; re-adding edits the existing edge). */
    @RequirePermission(Permission.PERSON_UPDATE)
    public void addConnection(int personId, int targetPersonId, String type, Integer strength, String note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (personId == targetPersonId) {
            throw new BadRequestException("A contact cannot be connected to itself");
        }
        requirePerson(workspaceId, personId);
        requirePerson(workspaceId, targetPersonId);

        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspaceId);
        edge.setSourcePersonId(Math.min(personId, targetPersonId));
        edge.setTargetPersonId(Math.max(personId, targetPersonId));
        edge.setType(EDGE_TYPES.contains(type) ? type : DEFAULT_TYPE);
        edge.setStrength(clampStrength(strength));
        edge.setNote(note);
        edgeMapper.upsert(edge);
    }

    /** Removes the connection between two contacts, if any. */
    @RequirePermission(Permission.PERSON_UPDATE)
    public void removeConnection(int personId, int targetPersonId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        edgeMapper.delete(workspaceId, Math.min(personId, targetPersonId), Math.max(personId, targetPersonId));
    }

    /**
     * Shortest warm-introduction path to the target: a breadth-first search over the mutual-connection
     * graph starting from every contact the team already engages, so the result has the fewest hops.
     * Returns {@code directlyKnown} when the target is itself already engaged.
     */
    public IntroPathDto findIntroPath(int targetPersonId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, targetPersonId);

        Set<Integer> engaged = new HashSet<>(personMapper.getEngagedPersonIds(workspaceId));
        if (engaged.contains(targetPersonId)) {
            return new IntroPathDto(true, true, List.of(personStep(workspaceId, targetPersonId, null, engaged)));
        }

        Map<Integer, List<Neighbor>> adjacency = buildAdjacency(workspaceId);
        Map<Integer, Integer> previous = new HashMap<>();
        Map<Integer, String> previousType = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int source : engaged) {
            if (visited.add(source)) queue.add(source);
        }

        boolean found = false;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == targetPersonId) {
                found = true;
                break;
            }
            for (Neighbor neighbor : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(neighbor.id())) {
                    previous.put(neighbor.id(), current);
                    previousType.put(neighbor.id(), neighbor.type());
                    queue.add(neighbor.id());
                }
            }
        }

        if (!found) {
            return new IntroPathDto(false, false, List.of());
        }

        List<Integer> order = new ArrayList<>();
        for (Integer node = targetPersonId; node != null; node = previous.get(node)) {
            order.add(node);
        }
        Collections.reverse(order);

        List<IntroPathStepDto> steps = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            int id = order.get(i);
            String connectionType = i == 0 ? null : previousType.get(id);
            steps.add(personStep(workspaceId, id, connectionType, engaged));
        }
        return new IntroPathDto(true, false, steps);
    }

    private Map<Integer, List<Neighbor>> buildAdjacency(int workspaceId) {
        Map<Integer, List<Neighbor>> adjacency = new HashMap<>();
        for (PersonEdge edge : edgeMapper.getAllEdges(workspaceId)) {
            adjacency.computeIfAbsent(edge.getSourcePersonId(), k -> new ArrayList<>())
                .add(new Neighbor(edge.getTargetPersonId(), edge.getType()));
            adjacency.computeIfAbsent(edge.getTargetPersonId(), k -> new ArrayList<>())
                .add(new Neighbor(edge.getSourcePersonId(), edge.getType()));
        }
        return adjacency;
    }

    private IntroPathStepDto personStep(int workspaceId, int personId, String connectionType, Set<Integer> engaged) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        String name = person == null ? null : person.getName();
        String companyName = (person == null || person.getCompany() == null) ? null : person.getCompany().getName();
        return new IntroPathStepDto(personId, name, companyName, connectionType, engaged.contains(personId));
    }

    private Person requirePerson(int workspaceId, int personId) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        return person;
    }

    private static int clampStrength(Integer strength) {
        if (strength == null) return 2;
        return Math.max(1, Math.min(3, strength));
    }

    private record Neighbor(int id, String type) {}
}

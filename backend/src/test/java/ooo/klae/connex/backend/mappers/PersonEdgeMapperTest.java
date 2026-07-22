package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.PersonConnectionDto;

class PersonEdgeMapperTest extends AbstractMapperTest {

    @Autowired private PersonEdgeMapper personEdgeMapper;

    @Test
    void topConnectionsAppliesLimitAfterDeterministicStrengthOrderingAcrossBothEndpoints() {
        Person topCharlie = person("Top Charlie");
        Person topAlpha = person("Top Alpha");
        Person topFoxtrot = person("Top Foxtrot");
        Person focal = person("Focal Person");
        Person topBravo = person("Top Bravo");
        Person topEcho = person("Top Echo");
        Person topDelta = person("Top Delta");
        Person topGolf = person("Top Golf");
        Person topHotel = person("Top Hotel");

        connect(focal, topCharlie, 3);
        connect(focal, topAlpha, 3);
        connect(focal, topFoxtrot, 2);
        connect(focal, topBravo, 3);
        connect(focal, topEcho, 2);
        connect(focal, topDelta, 2);
        connect(focal, topGolf, 1);
        connect(focal, topHotel, 1);

        List<PersonConnectionDto> connections = personEdgeMapper.getTopConnections(
            workspace.getId(), focal.getId(), 5);

        assertEquals(List.of("Top Alpha", "Top Bravo", "Top Charlie", "Top Delta", "Top Echo"),
            connections.stream().map(PersonConnectionDto::getPersonName).toList());
        assertEquals(List.of(3, 3, 3, 2, 2),
            connections.stream().map(PersonConnectionDto::getStrength).toList());
    }

    @Test
    void topConnectionsFiltersRestrictedAndBlankCounterpartsBeforeLimit() {
        Person focal = person("Focal Person");
        Person suspendedOne = person("Blocked Suspended A");
        Person suspendedTwo = person("Blocked Suspended B");
        Person ceasedOne = person("Blocked Ceased A");
        Person ceasedTwo = person("Blocked Ceased B");
        Person blank = person(" ");

        connect(focal, suspendedOne, 3);
        connect(focal, suspendedTwo, 3);
        connect(focal, ceasedOne, 3);
        connect(focal, ceasedTwo, 3);
        connect(focal, blank, 3);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspendedOne.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspendedTwo.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceasedOne.getId(), false, true);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceasedTwo.getId(), false, true);

        for (String suffix : List.of("A", "B", "C", "D", "E")) {
            connect(focal, person("Allowed " + suffix), 2);
        }

        List<PersonConnectionDto> connections = personEdgeMapper.getTopConnections(
            workspace.getId(), focal.getId(), 5);

        assertEquals(List.of("Allowed A", "Allowed B", "Allowed C", "Allowed D", "Allowed E"),
            connections.stream().map(PersonConnectionDto::getPersonName).toList());
    }

    private Person person(String name) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        personMapper.insert(person);
        return person;
    }

    private void connect(Person left, Person right, int strength) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(left.getId(), right.getId()));
        edge.setTargetPersonId(Math.max(left.getId(), right.getId()));
        edge.setType("knows");
        edge.setStrength(strength);
        personEdgeMapper.upsert(edge);
    }
}

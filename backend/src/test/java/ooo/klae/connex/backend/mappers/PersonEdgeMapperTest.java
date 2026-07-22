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
        Person suspendedSource = person("Blocked Suspended Source");
        Person ceasedSource = person("Blocked Ceased Source");
        Person blankSource = person("\t");
        Person noBreakSpaceSource = person("\u00A0");
        Person fileSeparatorSource = person("\u001C");
        Person focal = person("Focal Person");
        Person suspendedTarget = person("Blocked Suspended Target");
        Person ceasedTarget = person("Blocked Ceased Target");
        Person blankTarget = person(" ");
        Person noBreakSpaceTarget = person("\u00A0");
        Person fileSeparatorTarget = person("\u001C");

        connect(focal, suspendedSource, 3);
        connect(focal, ceasedSource, 3);
        connect(focal, blankSource, 3);
        connect(focal, noBreakSpaceSource, 3);
        connect(focal, fileSeparatorSource, 3);
        connect(focal, suspendedTarget, 3);
        connect(focal, ceasedTarget, 3);
        connect(focal, blankTarget, 3);
        connect(focal, noBreakSpaceTarget, 3);
        connect(focal, fileSeparatorTarget, 3);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspendedSource.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceasedSource.getId(), false, true);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspendedTarget.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceasedTarget.getId(), false, true);

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

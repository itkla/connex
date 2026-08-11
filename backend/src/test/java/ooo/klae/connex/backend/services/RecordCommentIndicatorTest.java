package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.dto.RecordCommentIndicatorDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ShareMapper;

class RecordCommentIndicatorTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired ShareMapper shareMapper;
    @Autowired WorkspaceService workspaceService;

    @Test
    void groupedCountsIncludeOnlyOpenThreads() {
        Person first = newPerson(newCompany());
        Person second = newPerson(newCompany());
        RecordCommentThread resolved = recordCommentService.createThread(
            "person", first.getId(), "Resolved", token());
        recordCommentService.createThread("person", first.getId(), "Open one", token());
        recordCommentService.createThread("person", second.getId(), "Open two", token());
        recordCommentService.resolve(resolved.getId(), resolved.getVersion());

        Map<Integer, RecordCommentIndicatorDto> indicators = recordCommentService
            .getIndicators("person", List.of(first.getId(), second.getId()))
            .stream()
            .collect(Collectors.toMap(
                RecordCommentIndicatorDto::targetId,
                Function.identity()));

        assertEquals(2, indicators.size());
        assertEquals(1, indicators.get(first.getId()).openThreads());
        assertEquals(1, indicators.get(second.getId()).openThreads());
    }

    @Test
    void otherWorkspaceThreadsOnASharedPersonAreNotCounted() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace(
            "Indicator Owner " + unique(), currentUser.getId());
        authenticateAs(currentUser, owner.getId());
        WorkspaceMembershipDto grantee = workspaceService.createWorkspace(
            "Indicator Grantee " + unique(), currentUser.getId());
        Person person = personIn(owner.getId());
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), owner.getId(), grantee.getId(), currentUser.getId(), false));
        recordCommentService.createThread("person", person.getId(), "Owner thread", token());

        authenticateAs(currentUser, grantee.getId());
        recordCommentService.createThread("person", person.getId(), "Grantee thread", token());

        List<RecordCommentIndicatorDto> indicators = recordCommentService.getIndicators(
            "person", List.of(person.getId()));
        assertEquals(1, indicators.size());
        assertEquals(1, indicators.getFirst().openThreads());
    }

    @Test
    void invisibleTargetsAreSilentlyOmitted() {
        Person visible = newPerson(newCompany());
        recordCommentService.createThread("person", visible.getId(), "Visible", token());
        WorkspaceMembershipDto foreignWorkspace = workspaceService.createWorkspace(
            "Indicator Foreign " + unique(), currentUser.getId());
        Person invisible = personIn(foreignWorkspace.getId());
        authenticateAs(currentUser, workspace.getId());

        List<RecordCommentIndicatorDto> indicators = recordCommentService.getIndicators(
            "person", List.of(visible.getId(), invisible.getId()));

        assertEquals(1, indicators.size());
        assertEquals(visible.getId(), indicators.getFirst().targetId());
    }

    @Test
    void acceptsOneHundredIdsAndRejectsMore() {
        List<Integer> oneHundred = IntStream.rangeClosed(1, 100).boxed().toList();
        List<Integer> oneHundredAndOne = IntStream.rangeClosed(1, 101).boxed().toList();

        assertEquals(List.of(), recordCommentService.getIndicators("person", oneHundred));
        assertThrows(BadRequestException.class,
            () -> recordCommentService.getIndicators("person", oneHundredAndOne));
    }

    private Person personIn(int workspaceId) {
        Person person = new Person();
        person.setWorkspaceId(workspaceId);
        person.setName("Indicator Person " + unique());
        personMapper.insert(person);
        return person;
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}

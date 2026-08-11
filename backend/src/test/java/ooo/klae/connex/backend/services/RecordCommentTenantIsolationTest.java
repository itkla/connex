package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentReactionSummary;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.dto.RecordCommentIndicatorDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;

class RecordCommentTenantIsolationTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired RecordCommentMapper recordCommentMapper;
    @Autowired ShareMapper shareMapper;
    @Autowired WorkspaceService workspaceService;

    @Test
    void sharedPersonFeedsRemainLocalToEachAuthoringWorkspace() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace(
            "Comment Owner " + unique(), currentUser.getId());
        authenticateAs(currentUser, owner.getId());
        WorkspaceMembershipDto grantee = workspaceService.createWorkspace(
            "Comment Grantee " + unique(), currentUser.getId());
        Person person = personIn(owner.getId());
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), owner.getId(), grantee.getId(), currentUser.getId(), false));

        authenticateAs(currentUser, owner.getId());
        RecordCommentThread ownerThread = recordCommentService.createThread(
            "person", person.getId(), "Owner discussion", token());

        authenticateAs(currentUser, grantee.getId());
        assertEquals(List.of(), recordCommentService.listThreads(
            "person", person.getId(), "all", 20, 0));
        assertThrows(ResourceNotFoundException.class,
            () -> recordCommentService.getThread(ownerThread.getId()));
        RecordCommentThread granteeThread = recordCommentService.createThread(
            "person", person.getId(), "Grantee discussion", token());

        authenticateAs(currentUser, owner.getId());
        List<RecordCommentThread> ownerFeed = recordCommentService.listThreads(
            "person", person.getId(), "all", 20, 0);
        assertEquals(1, ownerFeed.size());
        assertEquals(ownerThread.getId(), ownerFeed.get(0).getId());
        assertThrows(ResourceNotFoundException.class,
            () -> recordCommentService.getThread(granteeThread.getId()));
    }

    @Test
    void unshareHidesFeedWithoutDeletingRows() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace(
            "Unshare Owner " + unique(), currentUser.getId());
        authenticateAs(currentUser, owner.getId());
        WorkspaceMembershipDto grantee = workspaceService.createWorkspace(
            "Unshare Grantee " + unique(), currentUser.getId());
        Person person = personIn(owner.getId());
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), owner.getId(), grantee.getId(), currentUser.getId(), false));

        authenticateAs(currentUser, grantee.getId());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Local overlay", token());
        assertEquals(1, recordCommentService.countThreads("person", person.getId(), "all"));

        authenticateAs(currentUser, owner.getId());
        assertEquals(1, shareMapper.unsharePerson(
            person.getId(), owner.getId(), grantee.getId()));
        authenticateAs(currentUser, grantee.getId());

        assertThrows(ResourceNotFoundException.class, () -> recordCommentService.listThreads(
            "person", person.getId(), "all", 20, 0));
        assertThrows(ResourceNotFoundException.class,
            () -> recordCommentService.getThread(thread.getId()));
        assertEquals(1, recordCommentMapper.countThreads(
            grantee.getId(), "person", person.getId(), "all"));
        assertEquals(1, recordCommentMapper.countCommentsInThread(
            grantee.getId(), thread.getId()));
    }

    @Test
    void reactionRowsAreInvisibleAcrossWorkspaces() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace(
            "Reaction Owner " + unique(), currentUser.getId());
        authenticateAs(currentUser, owner.getId());
        WorkspaceMembershipDto grantee = workspaceService.createWorkspace(
            "Reaction Grantee " + unique(), currentUser.getId());
        Person person = personIn(owner.getId());
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), owner.getId(), grantee.getId(), currentUser.getId(), false));
        RecordCommentThread ownerThread = recordCommentService.createThread(
            "person", person.getId(), "Owner reaction", token());
        long ownerCommentId = ownerThread.getComments().getFirst().getId();
        recordCommentService.toggleReaction(ownerCommentId, "heart");

        authenticateAs(currentUser, grantee.getId());

        assertEquals(List.of(), recordCommentMapper.getReactionSummaries(
            grantee.getId(), List.of(ownerCommentId), currentUser.getId()));

        authenticateAs(currentUser, owner.getId());
        List<RecordCommentReactionSummary> retained = recordCommentService
            .getThread(ownerThread.getId())
            .getComments()
            .getFirst()
            .getReactions();
        assertEquals(1, retained.size());
        assertEquals("heart", retained.getFirst().getReaction());

        authenticateAs(currentUser, grantee.getId());
        assertThrows(ResourceNotFoundException.class,
            () -> recordCommentService.toggleReaction(ownerCommentId, "heart"));
    }

    @Test
    void indicatorCountsStayWithinTheActiveWorkspace() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace(
            "Indicator Isolation Owner " + unique(), currentUser.getId());
        authenticateAs(currentUser, owner.getId());
        WorkspaceMembershipDto grantee = workspaceService.createWorkspace(
            "Indicator Isolation Grantee " + unique(), currentUser.getId());
        Person person = personIn(owner.getId());
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), owner.getId(), grantee.getId(), currentUser.getId(), false));
        recordCommentService.createThread("person", person.getId(), "Owner one", token());
        recordCommentService.createThread("person", person.getId(), "Owner two", token());

        authenticateAs(currentUser, grantee.getId());
        recordCommentService.createThread("person", person.getId(), "Grantee one", token());

        List<RecordCommentIndicatorDto> granteeIndicators = recordCommentService.getIndicators(
            "person", List.of(person.getId()));
        assertEquals(1, granteeIndicators.size());
        assertEquals(1, granteeIndicators.getFirst().openThreads());

        authenticateAs(currentUser, owner.getId());
        List<RecordCommentIndicatorDto> ownerIndicators = recordCommentService.getIndicators(
            "person", List.of(person.getId()));
        assertEquals(1, ownerIndicators.size());
        assertEquals(2, ownerIndicators.getFirst().openThreads());
    }

    private Person personIn(int workspaceId) {
        Person person = new Person();
        person.setWorkspaceId(workspaceId);
        person.setName("Shared " + unique());
        personMapper.insert(person);
        return person;
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}

package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
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

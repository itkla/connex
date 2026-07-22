package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

@Transactional(isolation = Isolation.READ_COMMITTED)
class FoundationServiceTest extends AbstractServiceTest {

    @Autowired DealService dealService;
    @Autowired TaskService taskService;
    @Autowired UserService userService;
    @Autowired WorkspaceService workspaceService;
    @Autowired PipelineService pipelineService;

    @Test
    void createDealDefaultsWorkspaceAndOwner() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = new Deal();
        deal.setName("Owned deal");
        deal.setValue(100);
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());

        Deal created = dealService.create(deal);

        assertEquals(workspace.getId(), created.getWorkspaceId());
        assertEquals(currentUser.getId(), created.getOwnerId());
    }

    @Test
    void ownerAndCollaboratorsRejectUsersOutsideWorkspace() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        User outsider = newUserInAnotherWorkspace();

        assertThrows(ForbiddenException.class, () -> dealService.updateOwner(deal.getId(), outsider.getId()));
        assertThrows(
            ForbiddenException.class,
            () -> dealService.replaceCollaborators(deal.getId(), List.of(outsider.getId()))
        );
    }

    @Test
    void taskCreateRejectsCrossWorkspaceAssignee() {
        User outsider = newUserInAnotherWorkspace();
        Task task = new Task();
        task.setDescription("Cross workspace");
        task.setAssignedTo(outsider);

        assertThrows(ForbiddenException.class, () -> taskService.create(task));
    }

    @Test
    void quickCompleteAllowsOnlyAssignee() {
        Task task = newTask(currentUser, null, null);

        Task completed = taskService.complete(task.getId());

        assertTrue(completed.isCompleted());

        User otherMember = newUser();
        authenticate(otherMember);
        assertThrows(ForbiddenException.class, () -> taskService.complete(task.getId()));
    }

    @Test
    void invalidTimezoneIsRejected() {
        assertThrows(
            BadRequestException.class,
            () -> userService.updateTimezone(currentUser.getId(), "Mars/Olympus_Mons")
        );
    }

    @Test
    void multipleMembershipsResolveToDefaultWorkspace() {
        Workspace other = new Workspace();
        other.setName("Second Workspace");
        other.setSlug("second-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), currentUser.getId(), "member");

        // Multi-membership is now supported. Off the request thread the resolver falls back
        // to the user's first/default workspace instead of failing closed.
        assertEquals(workspace.getId(), workspaceService.getCurrentWorkspaceId());
        assertDoesNotThrow(() -> dealService.getAllDeals());
    }

    @Test
    void adminOpsRejectPlainMembers() {
        Pipeline pipeline = newPipeline();

        // a plain member of the active workspace
        User member = newUser();
        authenticate(member);

        assertThrows(ForbiddenException.class, () -> pipelineService.deletePipeline(pipeline.getId()));
    }

    private User newUserInAnotherWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);

        String suffix = unique();
        User user = new User();
        user.setUsername("outside_" + suffix);
        user.setDisplayName("Outside " + suffix);
        user.setEmail("outside_" + suffix + "@example.com");
        user.setPasswordHash("hash");
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(other.getId(), user.getId(), "member");
        return user;
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }
}

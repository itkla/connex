package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

class FoundationServiceTest extends AbstractServiceTest {

    @Autowired DealService dealService;
    @Autowired TaskService taskService;
    @Autowired UserService userService;

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

        assertThrows(BadRequestException.class, () -> dealService.updateOwner(deal.getId(), outsider.getId()));
        assertThrows(
            BadRequestException.class,
            () -> dealService.replaceCollaborators(deal.getId(), List.of(outsider.getId()))
        );
    }

    @Test
    void taskCreateRejectsCrossWorkspaceAssignee() {
        User outsider = newUserInAnotherWorkspace();
        Task task = new Task();
        task.setDescription("Cross workspace");
        task.setAssignedTo(outsider);

        assertThrows(BadRequestException.class, () -> taskService.create(task));
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
    void multipleMembershipsFailClosed() {
        Workspace other = new Workspace();
        other.setName("Second Workspace");
        other.setSlug("second-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), currentUser.getId(), "member");

        assertThrows(IllegalStateException.class, () -> dealService.getAllDeals());
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
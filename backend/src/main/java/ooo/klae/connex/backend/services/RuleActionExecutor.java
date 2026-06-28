package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.notifications.InAppNotificationDispatcher;

/**
 * Performs a single rule {@link RuleAction} within an already-established automation context (the
 * caller has installed the security + tenant context). Each action delegates to the existing
 * tenant- and RBAC-enforcing service, so an actor lacking the action's permission fails the action.
 */
@Component
@RequiredArgsConstructor
public class RuleActionExecutor {

    private final TaskService taskService;
    private final ActivityService activityService;
    private final CompanyService companyService;
    private final PersonService personService;
    private final DealService dealService;
    private final InAppNotificationDispatcher notificationDispatcher;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_DUE_DAYS = 3;

    public void execute(RuleAction action, RuleFireContext ctx) {
        String type = action.getType() == null ? "" : action.getType().trim().toLowerCase();
        switch (type) {
            case "create_task" -> createTask(action, ctx);
            case "log_activity" -> logActivity(action, ctx);
            case "add_tag" -> addTag(action, ctx);
            case "notify" -> notify(action, ctx);
            default -> throw new BadRequestException("Unsupported action: " + action.getType());
        }
    }

    private void createTask(RuleAction action, RuleFireContext ctx) {
        Task task = new Task();
        task.setDescription(action.getTitle());
        int dueDays = action.getDueInDays() != null && action.getDueInDays() > 0 ? action.getDueInDays() : DEFAULT_DUE_DAYS;
        task.setDueDate(LocalDateTime.now().toLocalDate().plusDays(dueDays).format(DATE));
        User assignee = new User();
        assignee.setId(ctx.targetUserId());
        task.setAssignedTo(assignee);
        if ("person".equals(ctx.recordType())) {
            task.setPerson(person(ctx.entityId()));
        } else if ("deal".equals(ctx.recordType())) {
            task.setDeal(deal(ctx.entityId()));
        }
        taskService.create(task);
    }

    private void logActivity(RuleAction action, RuleFireContext ctx) {
        Activity activity = new Activity();
        activity.setType(action.getActivityType());
        activity.setSubject(action.getTitle() != null && !action.getTitle().isBlank() ? action.getTitle() : "Automated activity");
        activity.setNotes(action.getBody());
        if ("person".equals(ctx.recordType())) {
            activity.setPerson(person(ctx.entityId()));
        } else if ("deal".equals(ctx.recordType())) {
            activity.setDeal(deal(ctx.entityId()));
        }
        activityService.create(activity);
    }

    private void addTag(RuleAction action, RuleFireContext ctx) {
        switch (ctx.recordType()) {
            case "company" -> companyService.addTag(ctx.entityId(), action.getTagId());
            case "person" -> personService.addTag(ctx.entityId(), action.getTagId());
            case "deal" -> dealService.addTag(ctx.entityId(), action.getTagId());
            default -> throw new BadRequestException("Cannot tag record type: " + ctx.recordType());
        }
    }

    private void notify(RuleAction action, RuleFireContext ctx) {
        Notification notification = new Notification();
        notification.setWorkspaceId(ctx.workspaceId());
        notification.setRecipientId(ctx.targetUserId());
        notification.setType("rule");
        notification.setCategory(ctx.recordType());
        notification.setSeverity(action.getSeverity() != null && !action.getSeverity().isBlank() ? action.getSeverity() : "info");
        notification.setTitle(action.getTitle());
        notification.setBody(action.getBody() != null ? action.getBody() : "");
        notification.setActorLabel("Automation");
        notification.setSourceType(ctx.recordType());
        notification.setSourceId(ctx.entityId());
        notification.setDedupeKey("rule:" + ctx.ruleId() + ":" + ctx.entityId() + ":" + ctx.dedupeSuffix());
        notification.setTriggeredAt(LocalDateTime.now().format(TIMESTAMP));
        notificationDispatcher.dispatch(notification);
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        return person;
    }

    private static Deal deal(int id) {
        Deal deal = new Deal();
        deal.setId(id);
        return deal;
    }
}

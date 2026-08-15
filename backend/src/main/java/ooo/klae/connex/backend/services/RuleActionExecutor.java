package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/**
 * Performs a single rule {@link RuleAction} within an already-established automation context (the
 * caller has installed the security + tenant context). Each action delegates to the existing
 * tenant- and RBAC-enforcing service, so an actor lacking the action's permission fails the action.
 *
 * <p>A {@code document} run's subject is a {@code deal_document} row, which no record-attaching
 * action can hold directly, so those actions attach to the document's parent deal instead.
 */
@Component
@RequiredArgsConstructor
public class RuleActionExecutor {

    private final TaskService taskService;
    private final ActivityService activityService;
    private final CompanyService companyService;
    private final PersonService personService;
    private final DealService dealService;
    private final NoteService noteService;
    private final NotificationDelivery notificationDelivery;
    private final TagMapper tagMapper;
    private final DealDocumentMapper documentMapper;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_DUE_DAYS = 3;

    public void execute(RuleAction action, AutomationActionContext ctx) {
        String type = action.getType() == null ? "" : action.getType().trim().toLowerCase();
        switch (type) {
            case "create_task" -> createTask(action, ctx);
            case "log_activity" -> logActivity(action, ctx);
            case "add_tag" -> addTag(action, ctx);
            case "remove_tag" -> removeTag(action, ctx);
            case "create_note" -> createNote(action, ctx);
            case "assign_owner" -> dealService.updateOwner(ctx.entityId(), action.getTargetUserId());
            case "change_stage" -> dealService.changeStage(ctx.entityId(), action.getTargetStageId());
            case "notify" -> notify(action, ctx);
            default -> throw new BadRequestException("Unsupported action: " + action.getType());
        }
    }

    private void createTask(RuleAction action, AutomationActionContext ctx) {
        Task task = new Task();
        task.setDescription(action.getTitle());
        int dueDays = action.getDueInDays() != null && action.getDueInDays() > 0 ? action.getDueInDays() : DEFAULT_DUE_DAYS;
        task.setDueDate(LocalDateTime.now().toLocalDate().plusDays(dueDays).format(DATE));
        User assignee = new User();
        assignee.setId(ctx.targetUserId());
        task.setAssignedTo(assignee);
        if ("person".equals(ctx.recordType())) {
            task.setPerson(person(ctx.entityId()));
        } else if (attachesToDeal(ctx)) {
            task.setDeal(deal(dealIdFor(ctx)));
        }
        taskService.create(task);
    }

    private void logActivity(RuleAction action, AutomationActionContext ctx) {
        Activity activity = new Activity();
        activity.setType(action.getActivityType());
        activity.setSubject(action.getTitle() != null && !action.getTitle().isBlank() ? action.getTitle() : "Automated activity");
        activity.setNotes(action.getBody());
        if ("person".equals(ctx.recordType())) {
            activity.setPerson(person(ctx.entityId()));
        } else if (attachesToDeal(ctx)) {
            activity.setDeal(deal(dealIdFor(ctx)));
        }
        activityService.create(activity);
    }

    private void addTag(RuleAction action, AutomationActionContext ctx) {
        switch (ctx.recordType()) {
            case "company" -> companyService.addTag(ctx.entityId(), action.getTagId());
            case "person" -> personService.addTag(ctx.entityId(), action.getTagId());
            case "deal" -> dealService.addTag(ctx.entityId(), action.getTagId());
            default -> throw new BadRequestException("Cannot tag record type: " + ctx.recordType());
        }
    }

    private void removeTag(RuleAction action, AutomationActionContext ctx) {
        if (tagMapper.getTagById(ctx.workspaceId(), action.getTagId()) == null) {
            throw new WorkflowExecutionException(
                WorkflowDiagnosticCode.ACTION_TAG_UNAVAILABLE.value(),
                "The workflow action is no longer executable.",
                true);
        }
        switch (ctx.recordType()) {
            case "company" -> companyService.removeTag(ctx.entityId(), action.getTagId());
            case "person" -> personService.removeTag(ctx.entityId(), action.getTagId());
            case "deal" -> dealService.removeTag(ctx.entityId(), action.getTagId());
            default -> throw new BadRequestException("Cannot remove tag from record type: " + ctx.recordType());
        }
    }

    private void createNote(RuleAction action, AutomationActionContext ctx) {
        Note note = new Note();
        note.setContent(action.getBody());
        if ("person".equals(ctx.recordType())) {
            note.setPerson(person(ctx.entityId()));
        } else if (attachesToDeal(ctx)) {
            note.setDeal(deal(dealIdFor(ctx)));
        }
        noteService.create(note);
    }

    private void notify(RuleAction action, AutomationActionContext ctx) {
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
        notification.setDedupeKey(ctx.notificationDedupeKey());
        notification.setTriggeredAt(LocalDateTime.now().format(TIMESTAMP));
        notificationDelivery.deliver(notification);
    }

    private static boolean attachesToDeal(AutomationActionContext ctx) {
        return "deal".equals(ctx.recordType()) || "document".equals(ctx.recordType());
    }

    /**
     * The deal an attaching action targets: the run's own record for a deal run, and the parent deal
     * of the subject document for a document run. A document whose parent vanished after the trigger
     * was published fails the action closed with the same fixed code as the record guard.
     */
    private int dealIdFor(AutomationActionContext ctx) {
        if (!"document".equals(ctx.recordType())) {
            return ctx.entityId();
        }
        Integer dealId = documentMapper.findDealIdById(ctx.workspaceId(), ctx.entityId());
        if (dealId == null) {
            throw new WorkflowExecutionException(
                "record_unavailable",
                "The workflow record is no longer available for automation.",
                true);
        }
        return dealId;
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

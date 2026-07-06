package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/**
 * Business logic for logging and retrieving {@code Deal} records.
 * Handles mapping between {@code DealDto} and {@code Deal} bean.
 * Delegates persistence to {@code DealMapper}.
 */

@Service
@RequiredArgsConstructor
public class DealService {
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final RuleTriggerPublisher ruleTriggers;
    private final NotificationChangePublisher notificationChanges;
    private final CustomFieldValueService customFieldValueService;
    private final ReferenceService referenceService;
    private final CompanyMapper companyMapper;
    private final UserMapper userMapper;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final DealStageHistoryService dealStageHistoryService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String MENTION_TYPE = "deal.mention";
    private static final String MENTION_CATEGORY = "deal";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "value", "actualValue", "currency", "pipelineId", "stageId",
               "companyId", "expectedCloseDate", "closedAt", "closedReason", "won");

    /**
     * Reconciles a deal's close fields so {@code won} and {@code closedAt} always agree.
     * The outcome is explicit and stage-independent: {@code won} (TRUE=won, FALSE=lost,
     * NULL=open) is set by the client and may be set at ANY stage — a deal can win or lose
     * mid-pipeline. {@code closedAt} follows {@code won} (stamped when an outcome exists,
     * cleared when open). As a convenience, a deal sitting on a terminal (success/failure)
     * stage is forced to that outcome — moving a deal onto "Closed Won" still wins it.
     * @param deal
     */
    private void reconcileCloseState(Deal deal) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Integer stageId = deal.getStageId();
        String stageOutcome = stageId != null ? dealMapper.getStageOutcome(workspaceId, stageId) : "normal";
        if ("won".equals(stageOutcome)) {
            deal.setWon(true);
        } else if ("lost".equals(stageOutcome)) {
            deal.setWon(false);
        }
        // closed_at follows the outcome: present iff the deal has a won/lost result.
        if (deal.getWon() == null) {
            deal.setClosedAt(null);
            deal.setClosedReason(null);
        } else if (deal.getClosedAt() == null || deal.getClosedAt().isBlank()) {
            deal.setClosedAt(LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME));
        }
    }

    /**
     * Re-derives the deal's {@code closedReason} @/# references and notifies newly-mentioned members.
     * Passing a null/blank reason (e.g. a reopened deal, whose reason was just cleared) purges the set.
     */
    private void syncClosedReasonMentions(int workspaceId, Deal deal) {
        User actor = currentActorOrNull();
        List<Integer> mentioned = referenceService.syncReferences(
            workspaceId, ReferenceService.SOURCE_DEAL, deal.getId(), deal.getClosedReason());
        if (actor != null) {
            notifyMentions(workspaceId, deal, mentioned, actor);
        }
    }

    private Deal hydrateReferences(int workspaceId, Deal deal) {
        deal.setReferences(referenceService.referencesFor(workspaceId, ReferenceService.SOURCE_DEAL, deal.getId()));
        return deal;
    }

    private User currentActorOrNull() {
        try {
            return authService.getCurrentUser();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void notifyMentions(int workspaceId, Deal deal, List<Integer> recipientIds, User actor) {
        if (recipientIds.isEmpty()) {
            return;
        }
        String snippet = snippet(deal.getClosedReason());
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME);
        String actionUrl = "/records/deals/" + deal.getId();
        for (int recipientId : recipientIds) {
            if (recipientId == actor.getId()) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(recipientId, MENTION_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(MENTION_TYPE);
                notification.setCategory(MENTION_CATEGORY);
                notification.setSeverity(MENTION_SEVERITY);
                notification.setTemplateVersion(1);
                notification.setTitle("New mention");
                notification.setBody(actor.getDisplayName() + " mentioned you in a deal");
                notification.setActorId(actor.getId());
                notification.setActorLabel(actor.getDisplayName());
                notification.setSourceType("deal");
                notification.setSourceId(deal.getId());
                notification.setSourceLabel(snippet);
                notification.setActionUrl(actionUrl);
                notification.setDedupeKey(MENTION_TYPE + ":" + deal.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("dealId", deal.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }

    /**
     * Retrieves all {@code Deal} records.
     * @return
     */
    public List<Deal> getAllDeals() {
        return dealMapper.getAllDeals(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Retrieves all {@code Deal} records by pipeline ID.
     * @param pipelineId
     * @return
     */
    public List<Deal> getDealsByPipelineId(int pipelineId) {
        return dealMapper.getDealsByPipelineId(workspaceService.getCurrentWorkspaceId(), pipelineId);
    }

    /**
     * Retrieves all {@code Deal} records by stage ID.
     * @param stageId
     * @return
     */
    public List<Deal> getDealsByStageId(int stageId) {
        return dealMapper.getDealsByStageId(workspaceService.getCurrentWorkspaceId(), stageId);
    }

    /**
     * Retrieves all {@code Deal} records by company ID.
     * @param companyId
     * @return
     */
    public List<Deal> getDealsByCompanyId(int companyId) {
        return dealMapper.getDealsByCompanyId(workspaceService.getCurrentWorkspaceId(), companyId);
    }

    /**
     * Retrieves all {@code Deal} records by person ID.
     * @param personId
     * @return
     */
    public List<Deal> getDealsByPersonId(int personId) {
        return dealMapper.getDealsByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    /**
     * Retrieves all {@code Deal} records by tag ID.
     * @param tagId
     * @return
     */
    public List<Deal> getDealsByTagId(int tagId) {
        return dealMapper.getDealsByTagId(workspaceService.getCurrentWorkspaceId(), tagId);
    }

    /**
     * Retrieves a {@code Deal} by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     * @return
     */
    public Deal getDealById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, id);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        return hydrateReferences(workspaceId, deal);
    }

    /**
     * When the deal reached each stage, earliest first. Readable by any member who can see the deal.
     * @param id the deal to read history for
     * @return the deal's stage-achievement log
     */
    public List<DealStageHistory> getStageHistory(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, id) == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + id);
        }
        return dealStageHistoryService.getHistory(id);
    }

    /**
     * Name-resolved projection of a deal for hover previews / inline references:
     * stage, pipeline, company, and owner are hydrated to display names.
     *
     * @param id the deal to summarize
     * @return the workspace-scoped {@link DealSummaryDto}
     */
    public DealSummaryDto getDealSummary(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, id);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + id);

        String status = deal.getWon() == null ? "open" : deal.getWon() ? "won" : "lost";
        String pipelineName = null;
        if (deal.getPipelineId() != null) {
            Pipeline pipeline = pipelineMapper.getPipelineById(workspaceId, deal.getPipelineId());
            if (pipeline != null) pipelineName = pipeline.getName();
        }
        String stageName = null;
        if (deal.getStageId() != null) {
            Stage stage = pipelineMapper.getStageById(workspaceId, deal.getStageId());
            if (stage != null) stageName = stage.getName();
        }
        String companyName = null;
        if (deal.getCompanyId() != null) {
            Company company = companyMapper.getCompanyById(workspaceId, deal.getCompanyId());
            if (company != null) companyName = company.getName();
        }
        String ownerName = null;
        if (deal.getOwnerId() != null) {
            User owner = userMapper.getUserById(deal.getOwnerId());
            if (owner != null) ownerName = owner.getDisplayName();
        }
        return new DealSummaryDto(deal.getId(), deal.getName(), deal.getValue(), deal.getCurrency(),
            status, deal.getExpectedCloseDate(), stageName, pipelineName, companyName, ownerName);
    }

    /**
     * Creates a new {@code Deal} record.
     * @param deal
     * @return
     */
    @Transactional
    @RequirePermission(Permission.DEAL_CREATE)
    public Deal create(Deal deal) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        deal.setWorkspaceId(workspaceId);
        deal.setOwnerId(authService.getCurrentUser().getId());
        reconcileCloseState(deal);
        if (deal.getStageId() != null) {
            deal.setPosition(dealMapper.nextDealPosition(workspaceId, deal.getStageId()));
        }
        dealMapper.insert(deal);
        if (deal.getStageId() != null) {
            dealStageHistoryService.record(workspaceId, deal.getId(), deal.getStageId());
        }
        auditService.record("deal.create", "deal", deal.getId(), deal.getName(),
            "Created deal " + deal.getName(),
            auditService.diff(null, deal, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", deal.getId());
        ruleTriggers.publish(workspaceId, "deal", deal.getId(), "deal.created");
        syncClosedReasonMentions(workspaceId, deal);
        return hydrateReferences(workspaceId, deal);
    }

    /**
     * Updates an existing {@code Deal} record.
     * @param id
     * @param deal
     * @return
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal update(int id, Deal deal) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        deal.setId(id);
        deal.setWorkspaceId(workspaceId);
        deal.setOwnerId(before.getOwnerId());
        Integer beforeStage = before.getStageId();
        Integer newStage = deal.getStageId();
        boolean stageChanged = newStage != null && (beforeStage == null || !beforeStage.equals(newStage));
        deal.setPosition(stageChanged
            ? dealMapper.nextDealPosition(workspaceId, newStage)
            : before.getPosition());
        reconcileCloseState(deal);
        dealMapper.update(deal);
        if (stageChanged) {
            dealStageHistoryService.record(workspaceId, id, newStage);
        }
        auditService.record("deal.update", "deal", id, deal.getName(),
            "Updated deal " + deal.getName(),
            auditService.diff(before, deal, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", id);
        ruleTriggers.publish(workspaceId, "deal", id, stageChanged ? "deal.stage_changed" : "deal.updated");
        if (Double.compare(before.getValue(), deal.getValue()) != 0) {
            ruleTriggers.publish(workspaceId, "deal", id, "deal.value_changed");
        }
        syncClosedReasonMentions(workspaceId, deal);
        return hydrateReferences(workspaceId, deal);
    }

    /**
     * Changes only a deal's expected close date, leaving name, value, pipeline, stage, outcome and
     * every other field untouched. Unlike {@link #update(int, Deal)} this cannot clobber other
     * fields — or reopen a concurrently-closed deal — from a stale client payload: it writes a
     * single column after confirming the deal belongs to the caller's workspace.
     * @param id the deal to reschedule
     * @param expectedCloseDate the target expected close date as a {@code YYYY-MM-DD} calendar day
     * @return the rescheduled deal
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal reschedule(int id, String expectedCloseDate) {
        try {
            LocalDate.parse(expectedCloseDate);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid deal expected close date: " + expectedCloseDate);
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        dealMapper.updateExpectedCloseDate(workspaceId, id, expectedCloseDate);
        Deal after = dealMapper.getDealById(workspaceId, id);
        auditService.record("deal.update", "deal", id, after.getName(),
            "Rescheduled deal " + after.getName(),
            auditService.singleChange("expectedCloseDate", before.getExpectedCloseDate(), expectedCloseDate));
        notificationChanges.publish(workspaceId, "deal", id);
        ruleTriggers.publish(workspaceId, "deal", id, "deal.updated");
        return after;
    }

    /**
     * Closes a deal as an atomic, intent-expressing operation: records the outcome
     * ({@code won} = true won / false lost), an optional reason and actual value, then
     * reconciles {@code closedAt}. Works at any stage and does not move the stage, so the
     * stage records where the deal was closed. Defaults to lost when no outcome is given.
     * @param id the deal id
     * @param won the outcome — TRUE = won, FALSE = lost (null defaults to lost)
     * @param reason optional close reason (ignored when blank)
     * @param actualValue optional realized value to record
     * @return the closed deal
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal close(int id, Boolean won, String reason, Double actualValue) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        Deal deal = dealMapper.getDealById(workspaceId, id);
        deal.setWon(won != null ? won : Boolean.FALSE);
        if (reason != null && !reason.isBlank()) deal.setClosedReason(reason);
        if (actualValue != null) deal.setActualValue(actualValue);
        reconcileCloseState(deal);
        dealMapper.update(deal);
        auditService.record("deal.close", "deal", id, deal.getName(),
            (Boolean.TRUE.equals(deal.getWon()) ? "Won deal " : "Lost deal ") + deal.getName(),
            auditService.diff(before, deal, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", id);
        ruleTriggers.publish(workspaceId, "deal", id, Boolean.TRUE.equals(deal.getWon()) ? "deal.won" : "deal.lost");
        syncClosedReasonMentions(workspaceId, deal);
        return hydrateReferences(workspaceId, deal);
    }

    /**
     * Reopens a deal.
     * @param id
     * @return
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal reopen(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        Deal deal = dealMapper.getDealById(workspaceId, id);
        deal.setWon(null); // reconcile clears closedAt + reason
        Integer stageId = deal.getStageId();
        boolean terminal = stageId != null && !"normal".equals(dealMapper.getStageOutcome(workspaceId, stageId));
        if (terminal) {
            Integer normalStage = deal.getPipelineId() != null
                ? dealMapper.getLastNormalStageId(workspaceId, deal.getPipelineId())
                : null;
            if (normalStage == null) {
                throw new BadRequestException(
                    "Cannot reopen deal \"" + deal.getName() + "\": its pipeline has no open stage to return to.");
            }
            deal.setStageId(normalStage);
            deal.setPosition(dealMapper.nextDealPosition(workspaceId, normalStage));
        }
        reconcileCloseState(deal);
        dealMapper.update(deal);
        if (terminal) {
            dealStageHistoryService.record(workspaceId, id, deal.getStageId());
        }
        auditService.record("deal.reopen", "deal", id, deal.getName(),
            "Reopened deal " + deal.getName(),
            auditService.diff(before, deal, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", id);
        syncClosedReasonMentions(workspaceId, deal);
        return hydrateReferences(workspaceId, deal);
    }

    /**
     * Custom-field values for a deal. Readable by any member who can see the deal.
     */
    public List<CustomFieldEntryDto> getCustomFields(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return customFieldValueService.getForEntity("deal", dealId);
    }

    /**
     * Replaces a deal's custom-field values.
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public List<CustomFieldEntryDto> updateCustomFields(int dealId, Map<Integer, Object> values) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return customFieldValueService.applyValues("deal", dealId, values);
    }

    /**
     * Sets or clears a single custom-field value on a deal.
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public List<CustomFieldEntryDto> updateCustomField(int dealId, int definitionId, Object value) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return customFieldValueService.applyValue("deal", dealId, definitionId, value);
    }

    /**
     * Filled custom-field values for many deals, keyed by deal id then definition id.
     */
    public Map<Integer, Map<Integer, Object>> getCustomFieldValues(List<Integer> dealIds) {
        return customFieldValueService.getForEntities("deal", dealIds);
    }

    /**
     * Deletes a {@code Deal} record by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     */
    @Transactional
    @RequirePermission(Permission.DEAL_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        customFieldValueService.deleteByEntity("deal", id);
        dealMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_DEAL, id);
        auditService.record("deal.delete", "deal", id, before.getName(),
            "Deleted deal " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", id);
    }

    /**
     * Retrieves the tags associated with a deal.
     * @param dealId
     * @return
     */
    public List<Tag> getTagsByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return tagMapper.getTagsByDealId(workspaceId, dealId);
    }

    /**
     * Adds a tag to a deal.
     * @param dealId
     * @param tagId
     */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void addTag(int dealId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        dealMapper.addTag(workspaceId, dealId, tagId);
        auditService.record("deal.addTag", "deal", dealId, deal.getName(),
            "Tagged " + deal.getName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Removes a tag from a deal.
     * @param dealId
     * @param tagId
     */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void removeTag(int dealId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        dealMapper.removeTag(workspaceId, dealId, tagId);
        String tagName = tag != null ? tag.getName() : "#" + tagId;
        auditService.record("deal.removeTag", "deal", dealId, deal.getName(),
            "Removed tag " + tagName + " from " + deal.getName(),
            auditService.singleChange("tag", tagName, null));
    }

    /**
     * Retrieves the people associated with a deal.
     * @param dealId
     * @return
     */
    public List<DealPerson> getPeopleByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return dealMapper.getDealPeopleByDealId(workspaceId, dealId);
    }

    /**
     * Adds a person to a deal.
     * @param dealId
     * @param personId
     * @param role
     */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void addPerson(int dealId, int personId, String role) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        dealMapper.addPerson(workspaceId, dealId, personId, role);
        String label = contactLabel(person.getName(), role);
        auditService.record("deal.addPerson", "deal", dealId, deal.getName(),
            "Linked " + label + " to " + deal.getName(),
            auditService.singleChange("contact", null, label));
    }

    /**
     * Updates the role of a person in a deal.
     * @param dealId
     * @param personId
     * @param role
     */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void updatePersonRole(int dealId, int personId, String role) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        DealPerson existing = dealMapper.getDealPeopleByDealId(workspaceId, dealId).stream()
            .filter(dp -> dp.getPerson() != null && dp.getPerson().getId() == personId)
            .findFirst().orElse(null);
        if (dealMapper.updatePersonRole(workspaceId, dealId, personId, role) == 0)
            throw new ResourceNotFoundException("Person " + personId + " is not associated with deal " + dealId);
        String name = existing != null && existing.getPerson() != null ? existing.getPerson().getName() : "#" + personId;
        String oldRole = existing != null ? existing.getRole() : null;
        auditService.record("deal.updatePersonRole", "deal", dealId, deal.getName(),
            "Changed " + name + "'s role on " + deal.getName(),
            auditService.singleChange("role", oldRole, role));
    }

    /**
     * Removes a person from a deal.
     * @param dealId
     * @param personId
     */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void removePerson(int dealId, int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Person person = personMapper.getPersonById(workspaceId, personId);
        dealMapper.removePerson(workspaceId, dealId, personId);
        String name = person != null ? person.getName() : "#" + personId;
        auditService.record("deal.removePerson", "deal", dealId, deal.getName(),
            "Unlinked " + name + " from " + deal.getName(),
            auditService.singleChange("contact", name, null));
    }

    /**
     * Replaces the tags associated with a deal.
     * @param dealId
     * @param tagIds
     * @return
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public List<Tag> replaceTags(int dealId, List<Integer> tagIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        List<String> before = tagMapper.getTagsByDealId(workspaceId, dealId).stream().map(Tag::getName).toList();
        dealMapper.clearTags(workspaceId, dealId);
        if (tagIds != null && !tagIds.isEmpty()) dealMapper.insertTags(workspaceId, dealId, tagIds);
        List<Tag> after = tagMapper.getTagsByDealId(workspaceId, dealId);
        auditService.record("deal.replaceTags", "deal", dealId, deal.getName(),
            "Updated tags on " + deal.getName(),
            auditService.singleChange("tags", before, after.stream().map(Tag::getName).toList()));
        return after;
    }

    /**
     * Replaces the people associated with a deal.
     * @param dealId
     * @param people
     * @return
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public List<DealPerson> replacePeople(int dealId, List<DealPerson> people) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        List<String> before = dealMapper.getDealPeopleByDealId(workspaceId, dealId).stream().map(DealService::personLabel).toList();
        dealMapper.clearPeople(workspaceId, dealId);
        if (people != null) {
            for (DealPerson dealPerson : people) {
                if (dealPerson == null || dealPerson.getPerson() == null) {
                    throw new BadRequestException("Each deal contact must include a person");
                }
                if (personMapper.getPersonById(workspaceId, dealPerson.getPerson().getId()) == null) {
                    throw new ResourceNotFoundException("Person not found with id: " + dealPerson.getPerson().getId());
                }
                dealMapper.addPerson(
                    workspaceId,
                    dealId,
                    dealPerson.getPerson().getId(),
                    dealPerson.getRole()
                );
            }
        }
        List<DealPerson> after = dealMapper.getDealPeopleByDealId(workspaceId, dealId);
        auditService.record("deal.replacePeople", "deal", dealId, deal.getName(),
            "Updated contacts on " + deal.getName(),
            auditService.singleChange("contacts", before, after.stream().map(DealService::personLabel).toList()));
        return after;
    }

    /**
     * Renders a deal-person association as "Name (Role)" (or just the name when no role is set) for audit summaries and change payloads.
     * @param name
     * @param role
     * @return
     */
    private static String contactLabel(String name, String role) {
        String safeName = name == null ? "?" : name;
        return (role == null || role.isBlank()) ? safeName : safeName + " (" + role + ")";
    }

    private static String personLabel(DealPerson dp) {
        String name = dp.getPerson() != null ? dp.getPerson().getName() : "?";
        return contactLabel(name, dp.getRole());
    }

    /**
     * Retrieves the activities associated with a deal.
     * @param dealId
     * @return
     */
    public List<Activity> getActivitiesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return referenceService.hydrateActivities(workspaceId, activityMapper.getActivitiesByDealId(workspaceId, dealId));
    }

    /**
     * Retrieves the notes associated with a deal.
     * @param dealId
     * @return
     */
    public List<Note> getNotesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByDealId(workspaceId, dealId, workspaceService.getCurrentUserId()));
    }

    /**
     * Retrieves the tasks associated with a deal.
     * @param dealId
     * @return
     */
    public List<Task> getTasksByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByDealId(workspaceId, dealId));
    }

    /**
     * Moves a deal to a different stage within its own pipeline, appending it to the end of the
     * target column. Thin wrapper over {@link #move(int, int, int)} kept for the bulk stage-change
     * and rule-engine callers that only care about the stage, not the ordinal position.
     * @param dealId the deal to move
     * @param stageId the target stage
     * @return the moved deal
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal changeStage(int dealId, int stageId) {
        return moveInternal(dealId, stageId, Integer.MAX_VALUE, true);
    }

    /**
     * Moves a deal to a target stage and ordinal position on the Kanban board, renumbering the
     * affected stage column(s) so positions stay contiguous and 0-based. The target stage must
     * belong to the active workspace and to the deal's pipeline; a cross-pipeline target is rejected
     * so the deal never ends up at a stage outside its pipeline. The close state is reconciled
     * exactly as a full update would, and {@code deal.stage_changed} fires only when the stage
     * actually changes — a within-column reorder publishes {@code deal.updated} instead.
     * @param dealId the deal to move
     * @param stageId the target stage
     * @param position the desired 0-based index within the target column (clamped to the column size)
     * @return the moved deal
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal move(int dealId, int stageId, int position) {
        return moveInternal(dealId, stageId, position, false);
    }

    /**
     * Shared implementation for {@link #move} and {@link #changeStage}. {@code forceStageChanged}
     * makes the {@code deal.stage_changed} trigger fire even when the stage value is unchanged,
     * preserving {@code changeStage}'s historical contract for the bulk stage-change and rule-engine
     * callers; the board {@code move} endpoint passes {@code false} so a pure reorder publishes
     * {@code deal.updated} instead.
     */
    private Deal moveInternal(int dealId, int stageId, int position, boolean forceStageChanged) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal before = dealMapper.getDealById(workspaceId, dealId);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Stage stage = pipelineMapper.getStageById(workspaceId, stageId);
        if (stage == null) throw new ResourceNotFoundException("Stage not found with id: " + stageId);
        Integer stagePipelineId = stage.getPipeline() != null ? stage.getPipeline().getId() : null;
        if (before.getPipelineId() != null && stagePipelineId != null
                && !before.getPipelineId().equals(stagePipelineId)) {
            throw new BadRequestException(
                "Stage " + stageId + " is not in deal " + dealId + "'s pipeline");
        }
        Integer oldStageId = before.getStageId();
        boolean stageChanged = oldStageId == null || oldStageId != stageId;
        boolean publishStageChanged = forceStageChanged || stageChanged;

        List<Integer> target = dealMapper.getDealIdsInStageOrdered(workspaceId, stageId);
        target.removeIf(existing -> existing == dealId);
        int index = Math.max(0, Math.min(position, target.size()));
        target.add(index, dealId);

        if (stageChanged && oldStageId != null) {
            List<Integer> source = dealMapper.getDealIdsInStageOrdered(workspaceId, oldStageId);
            source.removeIf(existing -> existing == dealId);
            for (int i = 0; i < source.size(); i++) {
                dealMapper.setPosition(workspaceId, source.get(i), i);
            }
        }

        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        deal.setStageId(stageId);
        deal.setPosition(index);
        reconcileCloseState(deal);
        dealMapper.update(deal);
        if (stageChanged) {
            dealStageHistoryService.record(workspaceId, dealId, stageId);
        }
        for (int i = 0; i < target.size(); i++) {
            int id = target.get(i);
            if (id != dealId) dealMapper.setPosition(workspaceId, id, i);
        }

        auditService.record("deal.update", "deal", dealId, deal.getName(),
            publishStageChanged ? "Moved deal " + deal.getName() + " to " + stage.getName()
                                : "Reordered deal " + deal.getName(),
            auditService.diff(before, deal, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "deal", dealId);
        ruleTriggers.publish(workspaceId, "deal", dealId, publishStageChanged ? "deal.stage_changed" : "deal.updated");
        return dealMapper.getDealById(workspaceId, dealId);
    }

    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public Deal updateOwner(int dealId, Integer ownerId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (ownerId != null) workspaceService.requireMember(workspaceId, ownerId);
        dealMapper.updateOwner(workspaceId, dealId, ownerId);
        if (ownerId != null) {
            dealMapper.removeCollaborator(workspaceId, dealId, ownerId);
        }
        auditService.record("deal.updateOwner", "deal", dealId, deal.getName(),
            "Updated owner on " + deal.getName(),
            auditService.singleChange("ownerId", deal.getOwnerId(), ownerId));
        notificationChanges.publish(workspaceId, "deal", dealId);
        if (!Objects.equals(deal.getOwnerId(), ownerId)) {
            ruleTriggers.publish(workspaceId, "deal", dealId, "deal.owner_changed");
        }
        return dealMapper.getDealById(workspaceId, dealId);
    }

    public List<User> getCollaborators(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (dealMapper.getDealById(workspaceId, dealId) == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return dealMapper.getCollaborators(workspaceId, dealId);
    }

    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public List<User> replaceCollaborators(int dealId, List<Integer> userIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        List<Integer> normalized = userIds == null ? List.of() : userIds.stream().distinct().toList();
        for (Integer userId : normalized) {
            if (userId == null) throw new BadRequestException("Collaborator IDs cannot be null");
            workspaceService.requireMember(workspaceId, userId);
        }
        normalized = normalized.stream()
            .filter(userId -> !userId.equals(deal.getOwnerId()))
            .toList();
        List<Integer> before = dealMapper.getCollaborators(workspaceId, dealId).stream()
            .map(User::getId)
            .toList();
        dealMapper.clearCollaborators(workspaceId, dealId);
        if (!normalized.isEmpty()) {
            dealMapper.insertCollaborators(workspaceId, dealId, normalized);
        }
        List<User> after = dealMapper.getCollaborators(workspaceId, dealId);
        auditService.record("deal.updateCollaborators", "deal", dealId, deal.getName(),
            "Updated collaborators on " + deal.getName(),
            auditService.singleChange("collaboratorIds", before, after.stream().map(User::getId).toList()));
        notificationChanges.publish(workspaceId, "deal", dealId);
        return after;
    }
}

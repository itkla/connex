package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.ContactChannelConsentEvent;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceVersion;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.RecordCommentDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.mappers.SequenceVersionMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the service-layer offboarding fan-out does what the dropped
 * cross-plane foreign keys used to (#440 increment 3): the RESTRICT-mirroring
 * guard refuses deletion while authored content exists, and the erasure
 * detaches/deletes every org-data reference without relying on the database —
 * the assertions run while the user row still exists, so no FK can have fired.
 */
class UserOffboardingServiceTest extends AbstractServiceTest {

    @Autowired private UserOffboardingService offboardingService;
    @Autowired private AiAssistantService aiAssistantService;
    @Autowired private AiChatMapper aiChatMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private SavedViewMapper savedViewMapper;
    @Autowired private SavedViewPreferenceMapper savedViewPreferenceMapper;
    @Autowired private UserDashboardMapper userDashboardMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private RelationshipSignalMapper relationshipSignalMapper;
    @Autowired private RecordCommentMapper recordCommentMapper;
    @Autowired private RecordCommentService recordCommentService;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private ConsentMapper consentMapper;
    @Autowired private SuppressionMapper suppressionMapper;
    @Autowired private IdentityBackfillTransaction identityBackfillTransaction;
    @Autowired private SequenceMapper sequenceMapper;
    @Autowired private SequenceVersionMapper sequenceVersionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void authoredNoteRefusesDeletion() {
        User target = newUser();
        newNote(target, null, null);
        assertThrows(ConflictException.class, () -> offboardingService.assertNoAuthoredContent(target.getId()));
    }

    @Test
    void createdActivityRefusesDeletion() {
        User target = newUser();
        newActivity(target, null, null);
        assertThrows(ConflictException.class, () -> offboardingService.assertNoAuthoredContent(target.getId()));
    }

    @Test
    void cleanAccountPassesTheGuard() {
        User target = newUser();
        offboardingService.assertNoAuthoredContent(target.getId());
    }

    @Test
    void erasureDetachesAndDeletesEveryReferenceWhileTheUserStillExists() throws Exception {
        User target = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        newPerson(company);
        identityBackfillTransaction.backfillPersonPage(
            null, workspace.getId(), 0, 500);
        identityBackfillTransaction.rebuildCollisionReport(null, workspace.getId());
        int dismissedDecisions = jdbcTemplate.update(
            "UPDATE duplicate_review_decision SET state = 'dismissed',"
                + " dismissed_at = UTC_TIMESTAMP(6), dismissed_by_user_id = ?"
                + " WHERE workspace_id = ? AND is_current = TRUE",
            target.getId(),
            workspace.getId());
        assertTrue(dismissedDecisions > 0);
        companyMapper.updateOwner(workspace.getId(), company.getId(), target.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), target.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        Company otherCompany = companyInWorkspace(otherWorkspace, target.getId());
        Person otherPerson = personInWorkspace(otherWorkspace, target.getId());
        int templateRoot = recordCreationTemplateFor(workspace.getId(), target.getId());
        int otherTemplateRoot = recordCreationTemplateFor(otherWorkspace.getId(), target.getId());
        Sequence sequence = sequenceFor(workspace.getId(), target.getId());
        Sequence otherSequence = sequenceFor(otherWorkspace.getId(), target.getId());
        SequenceVersion sequenceVersion = sequenceVersionFor(sequence, target.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);

        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), target.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(target.getId()));
        Task task = newTask(target, null, null);
        SavedView view = savedView(target);
        SavedView anotherView = savedView(newUser());
        savedViewPreferenceMapper.insertPin(workspace.getId(), target.getId(), anotherView.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), target.getId(), "company", anotherView.getId());
        userDashboardMapper.upsert(dashboard(target));
        newNotification(workspace.getId(), target.getId());
        Rule rule = ruleFor(target);
        ReportDefinition reportDefinition = reportDefinitionFor(target);
        ReportSnapshot reportSnapshot = reportSnapshotFor(reportDefinition, target);
        Campaign campaign = campaignFor(target);
        CampaignAudienceSnapshot campaignSnapshot = campaignSnapshotFor(campaign, target);
        ContactChannelConsentEvent consentEvent = consentEventFor(newPerson(company), target);
        SuppressionEntry suppression = suppressionFor(target);
        User retainedChatOwner = newUser();
        AiChatSession erasedChatSession = chatSession(target);
        AiChatMessage erasedChatMessage = chatMessage(
            erasedChatSession, target, "Erased account transcript");
        chatToolCall(erasedChatMessage, target, "erased_account_tool");
        chatTurn(erasedChatSession, target, "Erased account turn");
        AiChatSession retainedChatSession = chatSession(retainedChatOwner);
        AiChatMessage retainedChatMessage = chatMessage(
            retainedChatSession, retainedChatOwner, "Retained account transcript");
        chatToolCall(retainedChatMessage, retainedChatOwner, "retained_account_tool");
        chatTurn(retainedChatSession, retainedChatOwner, "Retained account turn");
        aiChatMapper.insertParticipant(
            workspace.getId(), erasedChatSession.getId(), retainedChatOwner.getId());
        aiChatMapper.insertParticipant(
            workspace.getId(), retainedChatSession.getId(), target.getId());
        User invitationRecipient = newUser();
        aiChatMapper.insertInvitation(
            workspace.getId(), retainedChatSession.getId(), invitationRecipient.getId(), target.getId());
        RecordCommentThread commentThread = new RecordCommentThread();
        commentThread.setWorkspaceId(workspace.getId());
        commentThread.setTargetType("person");
        commentThread.setTargetId(person.getId());
        commentThread.setCreatedByUserId(target.getId());
        commentThread.setState("open");
        recordCommentMapper.insertThread(commentThread);
        RecordComment retainedComment = new RecordComment();
        retainedComment.setWorkspaceId(workspace.getId());
        retainedComment.setThreadId(commentThread.getId());
        retainedComment.setAuthorUserId(target.getId());
        retainedComment.setContent("Retained account comment");
        retainedComment.setClientToken(UUID.randomUUID().toString());
        recordCommentMapper.insertComment(retainedComment);
        RecordComment redactedComment = new RecordComment();
        redactedComment.setWorkspaceId(workspace.getId());
        redactedComment.setThreadId(commentThread.getId());
        redactedComment.setAuthorUserId(currentUser.getId());
        redactedComment.setContent("Redacted account comment");
        redactedComment.setClientToken(UUID.randomUUID().toString());
        recordCommentMapper.insertComment(redactedComment);
        recordCommentMapper.softDeleteComment(
            workspace.getId(), redactedComment.getId(), target.getId());
        jdbcTemplate.update(
            "UPDATE record_comment_thread SET state = 'resolved', resolved_by_user_id = ?, "
                + "resolved_at = UTC_TIMESTAMP(6) WHERE workspace_id = ? AND id = ?",
            target.getId(), workspace.getId(), commentThread.getId());

        byte[] sequenceVersionBytesBefore = sequenceVersionRowBytes(sequenceVersion.getId());

        offboardingService.eraseOrgDataReferences(target.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertNull(companyMapper.getCompanyById(
            otherWorkspace.getId(), otherCompany.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(
            otherWorkspace.getId(), otherPerson.getId()).getOwnerId());
        assertTemplateActorsCleared(workspace.getId(), templateRoot);
        assertTemplateActorsCleared(otherWorkspace.getId(), otherTemplateRoot);
        assertSequenceActorsCleared(sequence.getId());
        assertSequenceActorsCleared(otherSequence.getId());
        assertArrayEquals(
            sequenceVersionBytesBefore,
            sequenceVersionRowBytes(sequenceVersion.getId()));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT published_by_id FROM sequence_version_publisher WHERE version_id = ?",
            Integer.class, sequenceVersion.getId()));
        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(savedViewMapper.getAccessibleById(workspace.getId(), target.getId(), view.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), target.getId(), anotherView.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(workspace.getId(), target.getId(), "company"));
        assertNull(userDashboardMapper.getByWorkspaceAndUser(workspace.getId(), target.getId()));
        assertEquals(0, notificationMapper.countPage(target.getId(), null, null, null, null));
        Rule after = ruleMapper.getById(workspace.getId(), rule.getId());
        assertNull(after.getRunAsUserId());
        assertNull(after.getCreatedById());
        assertNull(reportMapper.getDefinition(workspace.getId(), reportDefinition.getId()).getCreatedBy());
        assertNull(reportMapper.getSnapshot(
            workspace.getId(), reportDefinition.getId(), reportSnapshot.getId()).getGeneratedBy());
        Campaign clearedCampaign = campaignMapper.getCampaign(workspace.getId(), campaign.getId());
        assertNull(clearedCampaign.getOwnerUserId());
        assertNull(clearedCampaign.getCreatedById());
        assertNull(campaignMapper.getSnapshot(
            workspace.getId(), campaign.getId(), campaignSnapshot.getVersion()).getCreatedById());
        assertNull(jdbcTemplate.queryForObject(
            "SELECT created_by_id FROM contact_channel_consent_event WHERE id = ?",
            Integer.class, consentEvent.getId()));
        assertNull(suppressionMapper.getById(workspace.getId(), suppression.getId()).getCreatedById());
        AiChatSession erasedChatAfter = aiChatMapper.getAccessibleSessionById(
            workspace.getId(), retainedChatOwner.getId(), erasedChatSession.getId());
        AiChatMessage erasedMessageAfter = aiChatMapper.getMessageById(
            workspace.getId(), erasedChatSession.getId(), erasedChatMessage.getId());
        assertNotNull(erasedChatAfter);
        assertNull(erasedChatAfter.getCreatedByUserId());
        assertFalse(erasedChatAfter.isOwnedByCurrentUser());
        assertNull(aiChatMapper.getAccessibleSessionById(
            workspace.getId(), target.getId(), erasedChatSession.getId()));
        assertNotNull(erasedMessageAfter);
        assertNull(erasedMessageAfter.getAuthorUserId());
        assertEquals("Erased account transcript", erasedMessageAfter.getContent());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id = ? AND message_id = ?",
            Integer.class, workspace.getId(), erasedChatMessage.getId()));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT executed_by_user_id FROM ai_chat_tool_call WHERE workspace_id = ? AND message_id = ?",
            Integer.class, workspace.getId(), erasedChatMessage.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id = ? AND session_id = ?",
            Integer.class, workspace.getId(), erasedChatSession.getId()));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT requested_by_user_id FROM ai_chat_turn WHERE workspace_id = ? AND session_id = ?",
            Integer.class, workspace.getId(), erasedChatSession.getId()));
        RecordCommentThread retainedThread = recordCommentService.getThread(commentThread.getId());
        assertNull(retainedThread.getCreatedByUserId());
        assertNull(retainedThread.getResolvedByUserId());
        assertEquals(2, retainedThread.getComments().size());
        RecordComment retainedCommentAfter = retainedThread.getComments().getFirst();
        assertEquals("Retained account comment", retainedCommentAfter.getContent());
        assertNull(retainedCommentAfter.getAuthorUserId());
        assertNull(RecordCommentDto.from(retainedCommentAfter).author());
        RecordComment redactedCommentAfter = retainedThread.getComments().getLast();
        assertNull(redactedCommentAfter.getContent());
        assertNotNull(redactedCommentAfter.getDeletedAt());
        assertNull(redactedCommentAfter.getDeletedByUserId());
        assertFalse(aiChatMapper.isParticipant(
            workspace.getId(), retainedChatSession.getId(), target.getId()));
        var retainedInvitation = aiChatMapper.getParticipant(
            workspace.getId(), retainedChatSession.getId(), invitationRecipient.getId());
        assertNotNull(retainedInvitation);
        assertNull(retainedInvitation.getInvitedByUserId());
        assertTrue(aiChatMapper.isParticipant(
            workspace.getId(), erasedChatSession.getId(), retainedChatOwner.getId()));
        assertChatTranscriptOwnedBy(
            retainedChatSession, retainedChatMessage, retainedChatOwner);
        assertEquals(Integer.valueOf(retainedChatOwner.getId()), jdbcTemplate.queryForObject(
            "SELECT executed_by_user_id FROM ai_chat_tool_call WHERE workspace_id = ? AND message_id = ?",
            Integer.class, workspace.getId(), retainedChatMessage.getId()));
        assertEquals(Integer.valueOf(retainedChatOwner.getId()), jdbcTemplate.queryForObject(
            "SELECT requested_by_user_id FROM ai_chat_turn WHERE workspace_id = ? AND session_id = ?",
            Integer.class, workspace.getId(), retainedChatSession.getId()));
        assertEquals(target.getDisplayName(), userMapper.getUserById(target.getId()).getDisplayName());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM duplicate_review_decision WHERE dismissed_by_user_id = ?",
            Integer.class,
            target.getId()));
        assertEquals(dismissedDecisions, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM duplicate_review_decision WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void memberDetachmentCleansContentWhileTheMembershipStillExists() {
        User member = newUser();
        Pipeline pipeline = newPipeline();
        Company company = newCompany();
        Person person = newPerson(company);
        Deal deal = newDeal(pipeline, newStage(pipeline, 1), company);
        companyMapper.updateOwner(workspace.getId(), company.getId(), member.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), member.getId());
        dealMapper.updateOwner(workspace.getId(), deal.getId(), member.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        Company otherCompany = companyInWorkspace(otherWorkspace, member.getId());
        Person otherPerson = personInWorkspace(otherWorkspace, member.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(member.getId()));
        newNotification(workspace.getId(), member.getId());
        Task task = newTask(member, null, null);
        Campaign campaign = campaignFor(member);
        Sequence sequence = sequenceFor(workspace.getId(), member.getId());
        Sequence retainedOtherSequence = sequenceFor(otherWorkspace.getId(), member.getId());
        SavedView memberView = savedView(member);
        SavedView retainedOtherWorkspaceView = savedViewInWorkspace(otherWorkspace, member);
        SavedView sharedTarget = savedView(newUser());
        savedViewPreferenceMapper.insertPin(workspace.getId(), member.getId(), sharedTarget.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), member.getId(), "company", sharedTarget.getId());
        RelationshipSignal radarSignal = radarSignal();
        relationshipSignalMapper.insertState(
            workspace.getId(), radarSignal.getId(), member.getId(), "followed", null, null);

        offboardingService.detachMemberContent(workspace.getId(), member.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertEquals(member.getId(),
            companyMapper.getCompanyById(otherWorkspace.getId(), otherCompany.getId()).getOwnerId());
        assertEquals(member.getId(),
            personMapper.getPersonById(otherWorkspace.getId(), otherPerson.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
        assertEquals(0, notificationMapper.countPage(member.getId(), null, null, null, null));
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(campaignMapper.getCampaign(workspace.getId(), campaign.getId()).getOwnerUserId());
        assertNull(jdbcTemplate.queryForObject(
            "SELECT owner_id FROM sequence WHERE id = ?", Integer.class, sequence.getId()));
        assertEquals(member.getId(), jdbcTemplate.queryForObject(
            "SELECT owner_id FROM sequence WHERE id = ?",
            Integer.class, retainedOtherSequence.getId()));
        assertNull(savedViewMapper.getAccessibleById(
            workspace.getId(), member.getId(), memberView.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), member.getId(), sharedTarget.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(workspace.getId(), member.getId(), "company"));
        assertNotNull(savedViewMapper.getAccessibleById(
            otherWorkspace.getId(), member.getId(), retainedOtherWorkspaceView.getId()));
        assertNull(relationshipSignalMapper.getActiveForActor(
            workspace.getId(), radarSignal.getId(), member.getId()).getDisposition());
        assertNotNull(relationshipSignalMapper.getActiveForActor(
            workspace.getId(), radarSignal.getId(), currentUser.getId()));
        assertTrue(workspaceMapper.isMember(workspace.getId(), member.getId()));
    }

    @Test
    void memberRemovalRetainsOwnedChatTranscriptAndRemovesOnlyParticipantGrant() {
        User departing = newUser();
        User retainedOwner = newUser();
        AiChatSession departingSession = chatSession(departing);
        AiChatMessage departingMessage = chatMessage(
            departingSession, departing, "Departing member transcript");
        AiChatSession retainedSession = chatSession(retainedOwner);
        AiChatMessage retainedMessage = chatMessage(
            retainedSession, retainedOwner, "Retained member transcript");
        aiChatMapper.insertParticipant(
            workspace.getId(), retainedSession.getId(), departing.getId());

        offboardingService.detachMemberContent(workspace.getId(), departing.getId());
        workspaceMapper.removeMember(workspace.getId(), departing.getId());

        assertFalse(aiChatMapper.isParticipant(
            workspace.getId(), retainedSession.getId(), departing.getId()));
        assertFalse(workspaceMapper.isMember(workspace.getId(), departing.getId()));
        assertChatTranscriptOwnedBy(departingSession, departingMessage, departing);
        assertChatTranscriptOwnedBy(retainedSession, retainedMessage, retainedOwner);
    }

    @Test
    void freshMembershipPurgesResidualSavedViewDataOnlyInTheRejoinedWorkspace() {
        User returning = newUser();
        SavedView residualView = savedView(returning);
        SavedView sharedTarget = savedView(newUser());
        savedViewPreferenceMapper.insertPin(
            workspace.getId(), returning.getId(), sharedTarget.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), returning.getId(), "company", sharedTarget.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        SavedView retainedView = savedViewInWorkspace(otherWorkspace, returning);
        workspaceMapper.removeMember(workspace.getId(), returning.getId());

        offboardingService.prepareFreshMembership(workspace.getId(), returning.getId());

        assertNull(savedViewMapper.getAccessibleById(
            workspace.getId(), returning.getId(), residualView.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), returning.getId(), sharedTarget.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(
            workspace.getId(), returning.getId(), "company"));
        assertNotNull(savedViewMapper.getAccessibleById(
            otherWorkspace.getId(), returning.getId(), retainedView.getId()));
    }

    @Test
    void freshMembershipRetainsOwnedTranscriptAndRejoiningOwnerCanReadAndAppend() {
        User returning = newUser();
        User retainedOwner = newUser();
        AiChatSession returningSession = chatSession(returning);
        AiChatMessage returningMessage = chatMessage(
            returningSession, returning, "Returning member transcript");
        AiChatSession retainedSession = chatSession(retainedOwner);
        AiChatMessage retainedMessage = chatMessage(
            retainedSession, retainedOwner, "Other member transcript");
        aiChatMapper.insertParticipant(
            workspace.getId(), retainedSession.getId(), returning.getId());
        workspaceMapper.removeMember(workspace.getId(), returning.getId());

        assertTrue(aiChatMapper.isParticipant(
            workspace.getId(), retainedSession.getId(), returning.getId()));
        assertChatTranscriptOwnedBy(returningSession, returningMessage, returning);
        offboardingService.prepareFreshMembership(workspace.getId(), returning.getId());

        assertFalse(aiChatMapper.isParticipant(
            workspace.getId(), retainedSession.getId(), returning.getId()));
        assertChatTranscriptOwnedBy(returningSession, returningMessage, returning);
        assertChatTranscriptOwnedBy(retainedSession, retainedMessage, retainedOwner);

        workspaceMapper.addMember(workspace.getId(), returning.getId(), "admin");
        authenticateAs(returning, workspace.getId());
        var detail = aiAssistantService.get(returningSession.getId(), 1, 50);
        AiChatMessageCreateRequest appendRequest = new AiChatMessageCreateRequest();
        appendRequest.setContent("Message after rejoining");
        var appended = aiAssistantService.appendMessage(returningSession.getId(), appendRequest);

        assertEquals(returningSession.getId(), detail.session().getId());
        assertEquals(Integer.valueOf(returning.getId()), detail.session().getCreatedByUserId());
        assertEquals("Returning member transcript", detail.messages().items().getFirst().getContent());
        assertEquals("Message after rejoining", appended.getContent());
        assertEquals(2, aiChatMapper.countMessages(workspace.getId(), returningSession.getId()));
    }

    private Workspace newOtherWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private RelationshipSignal radarSignal() {
        RelationshipSignal signal = new RelationshipSignal();
        signal.setWorkspaceId(workspace.getId());
        signal.setFamily(RelationshipSignalDetectorService.RELATIONSHIP_DECAY);
        signal.setSubjectType("person");
        signal.setSubjectId(900_000 + Math.abs(unique().hashCode() % 90_000));
        signal.setSubjectLabel("Radar subject");
        signal.setPriority("cooling");
        signal.setPriorityRank(2);
        signal.setRankValue(10);
        signal.setDedupeKey("offboarding:" + unique());
        signal.setEvidenceJson("[]");
        signal.setRankExplanationJson("{\"rule\":\"priority_then_source_strength_then_subject\",\"factors\":[]}");
        signal.setEvidenceAsOf(java.time.LocalDateTime.of(2026, 8, 8, 12, 0));
        signal.setSourceStateHash("a".repeat(64));
        signal.setGenerationToken(unique());
        relationshipSignalMapper.upsertSignal(signal);
        return signal;
    }

    private Company companyInWorkspace(Workspace target, int ownerId) {
        Company company = new Company();
        company.setWorkspaceId(target.getId());
        company.setOwnerId(ownerId);
        company.setName("Company " + unique());
        companyMapper.insert(company);
        return company;
    }

    private Person personInWorkspace(Workspace target, int ownerId) {
        Person person = new Person();
        person.setWorkspaceId(target.getId());
        person.setOwnerId(ownerId);
        person.setName("Person " + unique());
        person.setEmail(unique() + "@example.com");
        personMapper.insert(person);
        return person;
    }

    private SavedView savedView(User owner) {
        return savedViewInWorkspace(workspace, owner);
    }

    private AiChatSession chatSession(User owner) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(owner.getId());
        session.setTitle("Offboarding session " + unique());
        session.setVisibility("shared");
        session.setStatus("active");
        aiChatMapper.insertSession(session);
        return session;
    }

    private AiChatMessage chatMessage(AiChatSession session, User author, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(session.getWorkspaceId());
        message.setSessionId(session.getId());
        message.setSeq(1);
        message.setAuthorKind("user");
        message.setAuthorUserId(author.getId());
        message.setContent(content);
        aiChatMapper.insertMessage(message);
        return message;
    }

    private void chatToolCall(AiChatMessage message, User executor, String toolName) {
        jdbcTemplate.update(
            "INSERT INTO ai_chat_tool_call "
                + "(workspace_id, message_id, tool_name, status, executed_by_user_id) "
                + "VALUES (?, ?, ?, 'executed', ?)",
            message.getWorkspaceId(), message.getId(), toolName, executor.getId());
    }

    private void chatTurn(AiChatSession session, User requester, String terminalReason) {
        jdbcTemplate.update(
            "INSERT INTO ai_chat_turn "
                + "(workspace_id, session_id, requested_by_user_id, status, terminal_reason) "
                + "VALUES (?, ?, ?, 'resolved', ?)",
            session.getWorkspaceId(), session.getId(), requester.getId(), terminalReason);
    }

    private void assertChatTranscriptOwnedBy(
            AiChatSession session, AiChatMessage message, User owner) {
        AiChatSession storedSession = aiChatMapper.getSessionById(
            session.getWorkspaceId(), owner.getId(), session.getId());
        AiChatMessage storedMessage = aiChatMapper.getMessageById(
            session.getWorkspaceId(), session.getId(), message.getId());
        assertNotNull(storedSession);
        assertEquals(Integer.valueOf(owner.getId()), storedSession.getCreatedByUserId());
        assertNotNull(storedMessage);
        assertEquals(Integer.valueOf(owner.getId()), storedMessage.getAuthorUserId());
        assertEquals(message.getContent(), storedMessage.getContent());
        assertEquals(1, aiChatMapper.countMessages(session.getWorkspaceId(), session.getId()));
    }

    private SavedView savedViewInWorkspace(Workspace targetWorkspace, User owner) {
        SavedView view = new SavedView();
        view.setWorkspaceId(targetWorkspace.getId());
        view.setUserId(owner.getId());
        view.setRecordType("company");
        view.setName("View " + unique());
        var config = objectMapper.createObjectNode();
        config.put("version", 1);
        view.setConfig(config);
        view.setVisibility("private");
        savedViewMapper.insert(view);
        return view;
    }

    private UserDashboard dashboard(User owner) {
        UserDashboard dashboard = new UserDashboard();
        dashboard.setWorkspaceId(workspace.getId());
        dashboard.setUserId(owner.getId());
        dashboard.setLayoutJson("{\"widgets\":[]}");
        return dashboard;
    }


    private Rule ruleFor(User user) {
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Rule " + unique());
        rule.setEnabled(false);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig("{}");
        rule.setActionsJson("[]");
        rule.setExecutionMode("user");
        rule.setRunAsUserId(user.getId());
        rule.setCreatedById(user.getId());
        ruleMapper.insert(rule);
        return rule;
    }

    private ReportDefinition reportDefinitionFor(User user) {
        ReportDefinition definition = new ReportDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setName("Report " + unique());
        definition.setCadence("monthly");
        definition.setConfigJson("{\"widgets\":[]}");
        definition.setCreatedBy(user.getId());
        reportMapper.insertDefinition(definition);
        return definition;
    }

    private ReportSnapshot reportSnapshotFor(ReportDefinition definition, User user) {
        ReportSnapshot snapshot = new ReportSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setReportDefinitionId(definition.getId());
        snapshot.setPeriodStart(LocalDate.of(2026, 6, 1));
        snapshot.setPeriodEnd(LocalDate.of(2026, 6, 30));
        snapshot.setComputedResult("{}");
        snapshot.setGeneratedBy(user.getId());
        reportMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private Campaign campaignFor(User user) {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Campaign " + unique());
        campaign.setType("email");
        campaign.setStatus("draft");
        campaign.setOwnerUserId(user.getId());
        campaign.setCreatedById(user.getId());
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private CampaignAudienceSnapshot campaignSnapshotFor(Campaign campaign, User user) {
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("company");
        snapshot.setDefinitionJson("{\"match\":\"all\",\"conditions\":[]}");
        snapshot.setChannel("email");
        snapshot.setPurpose("marketing");
        snapshot.setCreatedById(user.getId());
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private ContactChannelConsentEvent consentEventFor(Person person, User user) {
        ContactChannelConsent consent = new ContactChannelConsent();
        consent.setWorkspaceId(workspace.getId());
        consent.setPersonId(person.getId());
        consent.setChannel("email");
        consent.setPurpose("marketing");
        consent.setStatus("granted");
        consent.setSource("test");
        consentMapper.upsert(consent);
        ContactChannelConsentEvent event = new ContactChannelConsentEvent();
        event.setWorkspaceId(workspace.getId());
        event.setConsentId(consent.getId());
        event.setPersonId(person.getId());
        event.setChannel("email");
        event.setPurpose("marketing");
        event.setStatus("granted");
        event.setSource("test");
        event.setCreatedById(user.getId());
        consentMapper.insertEvent(event);
        return event;
    }

    private SuppressionEntry suppressionFor(User user) {
        SuppressionEntry entry = new SuppressionEntry();
        entry.setWorkspaceId(workspace.getId());
        entry.setScope("workspace");
        entry.setChannel("email");
        entry.setAddress(unique() + "@example.com");
        entry.setReason("manual");
        entry.setCreatedById(user.getId());
        suppressionMapper.insert(entry);
        return entry;
    }

    private Sequence sequenceFor(int workspaceId, int actorId) {
        Sequence sequence = new Sequence();
        sequence.setWorkspaceId(workspaceId);
        sequence.setName("Sequence " + unique());
        sequence.setOwnerId(actorId);
        sequence.setVisibility("personal");
        sequence.setStatus("draft");
        sequence.setTimezone("UTC");
        sequence.setWeekdayMask(31);
        sequence.setSendWindowStart(LocalTime.of(9, 0));
        sequence.setSendWindowEnd(LocalTime.of(17, 0));
        sequence.setCreatedById(actorId);
        sequence.setUpdatedById(actorId);
        sequenceMapper.insertSequence(sequence);
        return sequence;
    }

    private SequenceVersion sequenceVersionFor(Sequence sequence, int actorId) throws Exception {
        String definition = "{\"schemaVersion\":1,\"steps\":[]}";
        SequenceVersion version = new SequenceVersion();
        version.setWorkspaceId(sequence.getWorkspaceId());
        version.setSequenceId(sequence.getId());
        version.setVersionNumber(1);
        version.setDefinitionJson(definition);
        version.setDefinitionHash(MessageDigest.getInstance("SHA-256")
            .digest(definition.getBytes(StandardCharsets.UTF_8)));
        version.setPublishedById(actorId);
        sequenceVersionMapper.insertVersion(version);
        sequenceVersionMapper.insertVersionPublisher(
            sequence.getWorkspaceId(), version.getId(), actorId);
        return version;
    }

    private byte[] sequenceVersionRowBytes(long versionId) {
        return jdbcTemplate.queryForObject(
            "SELECT CAST(CONCAT_WS(0x1F, id, workspace_id, sequence_id, version_number,"
                + " HEX(CONVERT(definition_json USING utf8mb4)), HEX(definition_hash),"
                + " DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f')) AS BINARY)"
                + " FROM sequence_version WHERE id = ?",
            byte[].class,
            versionId);
    }

    private void assertSequenceActorsCleared(int sequenceId) {
        assertNull(jdbcTemplate.queryForObject(
            "SELECT owner_id FROM sequence WHERE id = ?", Integer.class, sequenceId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT created_by_id FROM sequence WHERE id = ?", Integer.class, sequenceId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT updated_by_id FROM sequence WHERE id = ?", Integer.class, sequenceId));
    }

    private int recordCreationTemplateFor(int workspaceId, int actorId) {
        jdbcTemplate.update(
            "INSERT IGNORE INTO record_creation_template_set (workspace_id, record_type) VALUES (?, 'person')",
            workspaceId);
        jdbcTemplate.update(
            "INSERT INTO record_creation_template "
                + "(workspace_id, record_type, status, position, created_by_id, updated_by_id) "
                + "VALUES (?, 'person', 'disabled', 0, ?, ?)",
            workspaceId, actorId, actorId);
        int rootId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        jdbcTemplate.update(
            "INSERT INTO record_creation_template_version "
                + "(workspace_id, template_id, version_number, name_en, name_ja, definition_json, "
                + "definition_hash, created_by_id) "
                + "VALUES (?, ?, 1, 'Template', 'テンプレート', "
                + "'{\"schemaVersion\":1,\"groups\":[]}', "
                + "UNHEX(SHA2('{\"schemaVersion\":1,\"groups\":[]}', 256)), ?)",
            workspaceId, rootId, actorId);
        long versionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update(
            "UPDATE record_creation_template SET current_version_id = ? WHERE workspace_id = ? AND id = ?",
            versionId, workspaceId, rootId);
        return rootId;
    }

    private void assertTemplateActorsCleared(int workspaceId, int rootId) {
        assertNull(jdbcTemplate.queryForObject(
            "SELECT created_by_id FROM record_creation_template WHERE workspace_id = ? AND id = ?",
            Integer.class, workspaceId, rootId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT updated_by_id FROM record_creation_template WHERE workspace_id = ? AND id = ?",
            Integer.class, workspaceId, rootId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT created_by_id FROM record_creation_template_version "
                + "WHERE workspace_id = ? AND template_id = ?",
            Integer.class, workspaceId, rootId));
    }
}

package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Data;

/**
 * Operator-facing subject disclosure. Notes arrive as an already serialized JSON array, so
 * assembly retains at most one page of note objects at a time.
 *
 * <p>The serialized array itself is retained in full: the response payload, and therefore the
 * peak heap of one disclosure, still grows with the total size of the subject's note bodies.
 * The array is not streamed to the servlet output because the disclosure is assembled inside one
 * routed, read-only tenant transaction and is released only after a durable disclosure audit
 * record exists; a {@code StreamingResponseBody} body runs after both of those have ended and
 * would inherit neither the tenant route nor the read snapshot.
 */
@Data
public class DataSubjectDisclosureDto {
    private long requestId;
    private int subjectWorkspaceId;
    private int subjectPersonId;
    private LocalDateTime generatedAt;
    private PersonDto person;
    private List<PersonIdentityDto> identities;
    private List<TagDto> tags;
    private List<CustomFieldValueDto> customFieldValues;
    private List<ActivityDto> activities;
    private List<ProviderCaptureEvidenceDto> providerCaptureEvidence;
    @JsonRawValue
    private String notes;
    private List<RecordCommentThreadDisclosureDto> recordCommentThreads;
    private List<TaskDto> tasks;
    private List<AttachmentDto> attachments;
    private List<EmploymentDto> employmentHistory;
    private List<LifecycleTransitionDto> lifecycleHistory;
    private List<QualificationAnswerDto> qualificationAnswers;
    private List<LifecyclePassDto> lifecyclePasses;
    private List<RelationshipEdgeDto> relationshipEdges;
    private List<DealAssociationDto> dealAssociations;
    private List<IntroductionDto> introductions;
    private List<ThirdPartyProvisionDto> thirdPartyProvisions;
    private List<ConsentStateDto> consentState;
    private List<ConsentHistoryDto> consentHistory;
    private List<AudienceExportEvidenceDto> audienceExportEvidence;
    private List<AuditEntryDto> auditTrail;
    private long auditTrailTotal;

    @Data
    public static class PersonDto {
        private int id;
        private int workspaceId;
        private String name;
        private String email;
        private String phone;
        private String title;
        private Integer companyId;
        private String companyName;
        private String imageUrl;
        private boolean riskExcluded;
        private boolean introExcluded;
        private LocalDateTime suspendedAt;
        private LocalDateTime provisionCeasedAt;
        private String lifecycleStage;
        private LocalDateTime lifecycleChangedAt;
        private String disqualifiedReason;
        private String disqualifiedReasonLabel;
        private String qualificationNotes;
        private String leadSource;
        private String leadSourceDetail;
        private Integer referrerPersonId;
        private LocalDateTime firstResponseDueAt;
        private LocalDateTime firstResponseStartedAt;
        private LocalDateTime firstRespondedAt;
        private LocalDateTime firstResponseBreachedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class PersonIdentityDto {
        private long id;
        private int workspaceId;
        private String kind;
        private String value;
        private String sourceSystem;
        private String sourceChannel;
        private String sourceExternalId;
        private String sourceRowRef;
        private LocalDateTime acquiredAt;
        private String purposeOfUseCode;
        private LocalDateTime supersededAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class TagDto {
        private int id;
        private int workspaceId;
        private String name;
        private String color;
    }

    @Data
    public static class CustomFieldValueDto {
        private int id;
        private int workspaceId;
        private int definitionId;
        private String fieldKey;
        private String label;
        private String fieldType;
        private String dataClassification;
        private String optionsJson;
        private boolean required;
        private int position;
        private boolean archived;
        private String valueText;
        private BigDecimal valueNumber;
        private LocalDateTime valueDate;
        private Boolean valueBool;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ActivityDto {
        private int id;
        private int workspaceId;
        private String type;
        private String subject;
        private String notes;
        private Integer personId;
        private Integer dealId;
        private Integer createdById;
        private LocalDateTime timestamp;
    }

    @Data
    public static class ProviderCaptureEvidenceDto {
        private long interactionId;
        private int workspaceId;
        private String provider;
        private String stream;
        private String providerSourceId;
        private String providerConversationId;
        private String interactionType;
        private String subject;
        private String body;
        private LocalDateTime occurredAt;
        private LocalDateTime endedAt;
        private String visibility;
        private String admissionStatus;
        private String admittedFieldsJson;
        private String materialExclusionsJson;
        private LocalDateTime capturedAt;
        private long participantId;
        private String participantRole;
        private String participantDisplayName;
        private String participantEmail;
        private String participantMatchState;
        private String participantHeldReason;
    }

    @Data
    public static class NoteDto {
        private int id;
        private int workspaceId;
        private String content;
        private String title;
        private Integer authorId;
        private Integer personId;
        private Integer dealId;
        private String visibility;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RecordCommentThreadDisclosureDto {
        private long id;
        private int workspaceId;
        private String targetType;
        private int targetId;
        private Integer createdByUserId;
        private String state;
        private Integer resolvedByUserId;
        private LocalDateTime resolvedAt;
        private int version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<RecordCommentDisclosureDto> comments = List.of();
    }

    @Data
    public static class RecordCommentDisclosureDto {
        private long id;
        private int workspaceId;
        private long threadId;
        private Integer authorUserId;
        private String content;
        private LocalDateTime createdAt;
        private LocalDateTime deletedAt;
        private Integer deletedByUserId;
    }

    @Data
    public static class TaskDto {
        private int id;
        private int workspaceId;
        private String description;
        private boolean completed;
        private String status;
        private int position;
        private LocalDate dueDate;
        private Integer assignedToId;
        private Integer personId;
        private Integer dealId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class AttachmentDto {
        private int id;
        private int workspaceId;
        private String fileName;
        private String contentType;
        private Long size;
        private Integer uploadedById;
        private LocalDateTime createdAt;
    }

    @Data
    public static class EmploymentDto {
        private int id;
        private int workspaceId;
        private int personId;
        private Integer companyId;
        private String companyName;
        private String title;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private LocalDateTime createdAt;
    }

    @Data
    public static class LifecycleTransitionDto {
        private long id;
        private int workspaceId;
        private int personId;
        private String fromStage;
        private String toStage;
        private String reason;
        private String reasonLabel;
        private String note;
        private Integer changedById;
        private LocalDateTime changedAt;
    }

    /**
     * One qualification answer recorded about the subject (#559). The criterion's own question text
     * travels with the answer, because "MET" discloses nothing on its own — the subject is entitled
     * to know what was asked as well as what was concluded.
     */
    /**
     * One lead-lifecycle pass recorded about the subject (#559). The pass retains its response and
     * breach timestamps after those values are cleared from the contact, so disclosing only the
     * live person fields would omit the subject's historical response record entirely.
     */
    @Data
    public static class LifecyclePassDto {
        private long id;
        private int workspaceId;
        private int personId;
        private LocalDateTime enteredAt;
        private LocalDateTime qualifiedAt;
        private LocalDateTime convertedAt;
        private LocalDateTime disqualifiedAt;
        private LocalDateTime endedAt;
        private LocalDateTime firstResponseStartedAt;
        private LocalDateTime firstRespondedAt;
        private LocalDateTime firstResponseDueAt;
        private LocalDateTime firstResponseBreachedAt;
        private Integer ownerId;
    }

    @Data
    public static class QualificationAnswerDto {
        private int workspaceId;
        private int personId;
        private int criterionId;
        private String criterionLabel;
        private String dimension;
        private String answer;
        private Integer answeredById;
        private LocalDateTime answeredAt;
    }

    @Data
    public static class RelationshipEdgeDto {
        private int id;
        private int workspaceId;
        private int sourcePersonId;
        private int targetPersonId;
        private int counterpartPersonId;
        private String counterpartPersonName;
        private String type;
        private int strength;
        private String note;
        private LocalDateTime createdAt;
    }

    @Data
    public static class DealAssociationDto {
        private int workspaceId;
        private int dealId;
        private String dealName;
        private int stageId;
        private String stageName;
        private String role;
    }

    @Data
    public static class IntroductionDto {
        private int id;
        private int workspaceId;
        private Integer introducerUserId;
        private int personAId;
        private String personAName;
        private int personBId;
        private String personBName;
        private String status;
        private String note;
        private LocalDateTime introducedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ThirdPartyProvisionDto {
        private int targetWorkspaceId;
        private String targetWorkspaceName;
        private Integer grantedBy;
        private boolean canEdit;
        private LocalDateTime createdAt;
    }

    /** Current channel/purpose consent, including the retained acquisition evidence. */
    @Data
    public static class ConsentStateDto {
        private int id;
        private int workspaceId;
        private int personId;
        private String channel;
        private String purpose;
        private String status;
        private String source;
        private String evidenceRef;
        private LocalDateTime capturedAt;
        private LocalDateTime updatedAt;
    }

    /** Retained consent transitions, including superseded evidence and nullable actor attribution. */
    @Data
    public static class ConsentHistoryDto {
        private int id;
        private int workspaceId;
        private int consentId;
        private int personId;
        private String channel;
        private String purpose;
        private String status;
        private String source;
        private String evidenceRef;
        private Integer createdById;
        private LocalDateTime createdAt;
    }

    /**
     * Subject-only membership in a retained audience export. Frozen and staged membership are
     * nullable for legacy records; staging records the intended request, not provider acceptance.
     * Counts and outcome classification describe the entire export. The subject provision outcome
     * is confirmed only for full staged-member delivery without a recorded late-outcome conflict;
     * partial, ambiguous and conflicting results remain unconfirmed. Legacy membership is unknown.
     */
    @Data
    public static class AudienceExportEvidenceDto {
        private int id;
        private int workspaceId;
        private int campaignId;
        private int snapshotId;
        private String channel;
        private String purpose;
        private String connector;
        private String externalListId;
        private String snapshotMemberStatus;
        private String snapshotExclusionReason;
        private Boolean frozenMember;
        private Boolean stagedForPush;
        private String subjectProvisionOutcome;
        private String status;
        private int attempt;
        private int totalMembers;
        private Integer pushedCount;
        private Integer failedCount;
        private String outcomeClassification;
        private String lateOutcome;
        private String failureReason;
        private LocalDateTime reconciliationRequiredAt;
        private Integer createdById;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class AuditEntryDto {
        private String action;
        private String actorLabel;
        private String outcome;
        private String summary;
        private LocalDateTime createdAt;
    }
}

package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

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
    private List<NoteDto> notes;
    private List<RecordCommentThreadDisclosureDto> recordCommentThreads;
    private List<TaskDto> tasks;
    private List<AttachmentDto> attachments;
    private List<EmploymentDto> employmentHistory;
    private List<LifecycleTransitionDto> lifecycleHistory;
    private List<RelationshipEdgeDto> relationshipEdges;
    private List<DealAssociationDto> dealAssociations;
    private List<IntroductionDto> introductions;
    private List<ThirdPartyProvisionDto> thirdPartyProvisions;
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
        private String qualificationNotes;
        private String leadSource;
        private String leadSourceDetail;
        private Integer referrerPersonId;
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
        private String note;
        private Integer changedById;
        private LocalDateTime changedAt;
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

    @Data
    public static class AuditEntryDto {
        private String action;
        private String actorLabel;
        private String outcome;
        private String summary;
        private LocalDateTime createdAt;
    }
}

package ooo.klae.connex.backend.tenant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reviewed APPI processing-restriction dispositions for mapper namespaces that
 * read person rows. Plane placement and tenant-interceptor enrollment remain
 * enforced by {@code TablePlaneArchTest},
 * {@code TenantRegistryCompletenessArchTest}, and {@code TenantScopeArchTest};
 * this registry records the separate restriction-sweep obligation.
 */
public final class ProcessingRestrictionRegistry {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private static final Map<String, RestrictionEnrollment> ENROLLMENTS = buildEnrollments();
    private static final Map<String, String> PERSON_READER_ALLOWLIST = buildAllowlist();

    private ProcessingRestrictionRegistry() {
    }

    /** Returns the immutable historical restriction-sweep enrollments. */
    public static Map<String, RestrictionEnrollment> enrollments() {
        return ENROLLMENTS;
    }

    /** Returns reviewed person-reader exceptions with nonblank rationales. */
    public static Map<String, String> personReaderAllowlist() {
        return PERSON_READER_ALLOWLIST;
    }

    /** Existing SQL behavior the architecture guard must verify honestly. */
    public enum RestrictionStrategy {
        EXCLUDE_SUSPENDED,
        EXCLUDE_PROVISION_CEASED,
        EXCLUDE_RESTRICTED,
        DETECT_RESTRICTED,
        INCLUDE_RESTRICTED_FOR_DISCLOSURE,
        PROJECT_RESTRICTION_STATE
    }

    /** One namespace's restriction strategy and review rationale. */
    public record RestrictionEnrollment(
            String mapperNamespace,
            RestrictionStrategy strategy,
            String rationale) {

        public RestrictionEnrollment {
            if (mapperNamespace == null || mapperNamespace.isBlank()
                    || strategy == null || rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("Restriction enrollment is incomplete");
            }
        }
    }

    private static Map<String, RestrictionEnrollment> buildEnrollments() {
        Map<String, RestrictionEnrollment> entries = new LinkedHashMap<>();
        enroll(entries, "CompanyMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Company person projections exclude suspended people.");
        enroll(entries, "DealMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Deal person projections exclude both suspended and provision-ceased people.");
        enroll(entries, "ReportMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Report person projections exclude suspended people.");
        enroll(entries, "PersonMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Ordinary person reads exclude suspended people while administrative mutations remain reachable.");
        enroll(entries, "DataSubjectDisclosureMapper",
            RestrictionStrategy.INCLUDE_RESTRICTED_FOR_DISCLOSURE,
            "Lawful disclosure deliberately includes restricted people and exports both restriction timestamps.");
        enroll(entries, "ShareMapper", RestrictionStrategy.EXCLUDE_PROVISION_CEASED,
            "Shared-person reads exclude records whose provisioning has ceased.");
        enroll(entries, "SegmentMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Segment person membership excludes suspended people.");
        enroll(entries, "CampaignMapper", RestrictionStrategy.DETECT_RESTRICTED,
            "Campaign validation deliberately detects both restriction states before excluding audience members.");
        enroll(entries, "PersonEdgeMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Relationship graph reads exclude suspended endpoints.");
        enroll(entries, "IdentityMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Backfill candidate reads and identity writes exclude both suspended and provision-ceased people.");
        enroll(entries, "IdentityCollisionMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Collision group reads exclude both suspended and provision-ceased people at read time.");
        enroll(entries, "DuplicateReviewMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Duplicate review pair summaries and materialized groups exclude suspended and provision-ceased people.");
        enroll(entries, "NotificationMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Notification person projections exclude suspended people.");
        enroll(entries, "IntroductionMapper", RestrictionStrategy.EXCLUDE_SUSPENDED,
            "Introduction person projections exclude suspended people.");
        enroll(entries, "PersonEmploymentMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Recent employment moves exclude both suspended and provision-ceased people.");
        enroll(entries, "PersonLifecyclePassMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Copying a lifecycle pass's response outcome from the contact excludes both suspended "
                + "and provision-ceased people; the pass's own timestamps carry no personal data.");
        enroll(entries, "ProviderCaptureMapper", RestrictionStrategy.DETECT_RESTRICTED,
            "Provider participant matching detects both restriction states before projection.");
        enroll(entries, "AiAssistantIdentifierMapper", RestrictionStrategy.EXCLUDE_RESTRICTED,
            "Ask Connex identifier resolution excludes both suspended and provision-ceased people.");
        return Map.copyOf(entries);
    }

    private static Map<String, String> buildAllowlist() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(MAPPERS + "ActivityMapper",
            "Retained activity associations remain reachable for compliance and erasure operations.");
        entries.put(MAPPERS + "AttachmentMapper",
            "Retention and erasure must reach attachments owned by restricted people.");
        entries.put(MAPPERS + "LegacyTenantUploadMigrationMapper",
            "Offline storage reconciliation must include restricted owners so binaries are not stranded.");
        entries.put(MAPPERS + "NoteMapper",
            "Retained note associations remain reachable for compliance and erasure operations.");
        entries.put(MAPPERS + "TagMapper",
            "Tag lookup is auxiliary to an already restriction-assessed person result.");
        entries.put(MAPPERS + "TaskMapper",
            "Retained task associations remain reachable for compliance and erasure operations.");
        entries.put(MAPPERS + "TenantLifecycleMapper",
            "Offboarding export and teardown deliberately reach restricted people and their managed images.");
        return Map.copyOf(entries);
    }

    private static void enroll(
            Map<String, RestrictionEnrollment> entries,
            String mapper,
            RestrictionStrategy strategy,
            String rationale) {
        String namespace = MAPPERS + mapper;
        entries.put(namespace, new RestrictionEnrollment(namespace, strategy, rationale));
    }
}

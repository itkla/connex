package ooo.klae.connex.backend.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reviewed archive-visibility dispositions for every mapper namespace that reads
 * {@code person} or {@code company} rows, plus the individually reviewed statements that
 * deliberately read an archived record anyway.
 *
 * <p>Archiving replaced the hard delete for those two record types (#854): the row survives, so a
 * statement that could never observe a deleted record must now carry
 * {@code archived_at IS NULL} to keep the same guarantee. This registry is the closed record of
 * that decision — the same shape as {@link ProcessingRestrictionRegistry}, but with two levels.
 *
 * <p>The <strong>namespace</strong> level ({@link ArchiveDisposition}) states the mapper's ordinary
 * read posture. The <strong>statement</strong> level ({@link StatementExemption}) is what actually
 * grants permission to project an archived contact's or company's identifying columns, and it grants
 * it for one named statement and one named set of table aliases only. There is deliberately no
 * disposition that exempts a whole namespace: a leak lives in a statement, so the waiver has to
 * live there too.
 *
 * <p>Every exemption carries an {@link ExemptionReason} whose claim {@code ArchiveVisibilityArchTest}
 * re-derives from the statement's own SQL — a lock must really lock, a storage-key read must really
 * touch nothing but the object key, a period aggregate must really be period-bounded. Free prose is
 * additional colour, never the justification, and there is no reason code meaning "some other layer
 * filters this", because that claim cannot be checked and was untrue the one time it was made.
 *
 * <p>{@code ArchiveVisibilityArchTest} fails the build when a person- or company-reading mapper has
 * no disposition here, when a statement projects an archived record without either the predicate or
 * a matching exemption, and when an exemption stops being true — so a future mapper that forgets the
 * predicate is a red build rather than a silent leak of archived records back into the product.
 */
public final class ArchiveVisibilityRegistry {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private static final int MIN_RATIONALE_LENGTH = 40;
    private static final Map<String, ArchiveDisposition> DISPOSITIONS = buildDispositions();
    private static final List<StatementExemption> EXEMPTIONS = buildExemptions();

    private ArchiveVisibilityRegistry() {
    }

    /** Returns the immutable archive-visibility disposition map keyed by mapper namespace. */
    public static Map<String, ArchiveDisposition> dispositions() {
        return DISPOSITIONS;
    }

    /** Returns the immutable, individually reviewed statement-level archive exemptions. */
    public static List<StatementExemption> exemptions() {
        return EXEMPTIONS;
    }

    /** How one mapper namespace treats archived contacts and companies. */
    public enum ArchiveStrategy {
        /** Every person/company read excludes archived rows. Evidence: {@code archived_at IS NULL}. */
        EXCLUDE_ARCHIVED,
        /**
         * The namespace reports the archived state so its service can exclude those records.
         * Evidence: {@code archived_at IS NOT NULL}.
         */
        DETECT_ARCHIVED,
        /**
         * The namespace serves both the active and the archived list, switching on a request flag.
         * Evidence: both {@code archived_at IS NULL} and {@code archived_at IS NOT NULL}.
         */
        ARCHIVE_TOGGLE,
        /**
         * The namespace joins the record tables only to resolve keys and never projects or filters
         * on an identifying column, so archived rows carry nothing into its results. Evidence: no
         * statement in the namespace references a person or company identifying column.
         */
        NO_RECORD_PROJECTION,
        /**
         * The namespace deliberately reaches archived rows because retention, compliance, export,
         * teardown, or lawful disclosure must not be narrowed by an archive. Evidence: at least one
         * {@link StatementExemption}, each of which is verified in turn — the strategy on its own
         * grants nothing.
         */
        REACH_ARCHIVED
    }

    /**
     * Why one statement may read an archived record. Each constant names a claim about the statement
     * that {@code ArchiveVisibilityArchTest} checks against the statement's own SQL.
     */
    public enum ExemptionReason {
        /**
         * A pessimistic lock taken by a write path that must be able to act on an archived record —
         * restoring it, or refusing an update against it. Checked: the statement locks
         * ({@code FOR UPDATE}).
         */
        RECORD_LIFECYCLE_LOCK,
        /**
         * Lawful APPI disclosure, which must reach retained data whatever its list visibility.
         * Checked: the namespace is enrolled in {@link ProcessingRestrictionRegistry} as
         * {@link ProcessingRestrictionRegistry.RestrictionStrategy#INCLUDE_RESTRICTED_FOR_DISCLOSURE},
         * so the same read is already reviewed as a disclosure path under the APPI registry.
         */
        LAWFUL_DISCLOSURE,
        /**
         * Object-storage reconciliation, which must enumerate the binaries of archived records so
         * none is stranded or wrongly reaped. Checked: the statement references no identifying column
         * beyond the stored object keys ({@code image_url}, {@code logo_url}).
         */
        OBJECT_STORAGE_KEYS_ONLY,
        /**
         * A closed-period figure over what actually happened, which excluding archived records would
         * retroactively rewrite. Checked: the statement is bounded by a report period parameter, so
         * an unbounded live worklist cannot claim this reason.
         */
        CLOSED_PERIOD_ANALYTICS
    }

    /** One namespace's archive strategy and review rationale. */
    public record ArchiveDisposition(
            String mapperNamespace,
            ArchiveStrategy strategy,
            String rationale) {

        public ArchiveDisposition {
            if (mapperNamespace == null || mapperNamespace.isBlank()
                    || strategy == null || rationale == null
                    || rationale.length() < MIN_RATIONALE_LENGTH) {
                throw new IllegalArgumentException("Archive disposition is incomplete");
            }
        }
    }

    /**
     * One reviewed statement that may read an archived contact or company, naming the exact table
     * aliases the waiver covers. A new archived read inside the same statement is not covered and
     * fails the build.
     */
    public record StatementExemption(
            String mapperNamespace,
            String statementId,
            Set<String> aliases,
            ExemptionReason reason,
            String rationale) {

        public StatementExemption {
            if (mapperNamespace == null || mapperNamespace.isBlank()
                    || statementId == null || statementId.isBlank()
                    || aliases == null || aliases.isEmpty()
                    || reason == null || rationale == null
                    || rationale.length() < MIN_RATIONALE_LENGTH) {
                throw new IllegalArgumentException("Archive statement exemption is incomplete");
            }
            aliases = Set.copyOf(aliases);
        }

        /** Returns the {@code namespace#statementId} key this exemption applies to. */
        public String key() {
            return mapperNamespace + "#" + statementId;
        }
    }

    private static Map<String, ArchiveDisposition> buildDispositions() {
        Map<String, ArchiveDisposition> entries = new LinkedHashMap<>();
        declare(entries, "PersonMapper", ArchiveStrategy.ARCHIVE_TOGGLE,
            "The shared `visible` fragment excludes archived contacts from every ordinary read and "
                + "`companyJoin` drops an archived employer from the contact's company projection; "
                + "only the browser page/count/ids statements opt into the archived set, owned-only, "
                + "so it can be reviewed and restored.");
        declare(entries, "CompanyMapper", ArchiveStrategy.ARCHIVE_TOGGLE,
            "The shared `visible` fragment excludes archived companies from every ordinary read; only "
                + "the browser page/count/ids statements opt into the archived set, owned-only. "
                + "`visiblePerson` and the engagement probes additionally exclude archived contacts "
                + "so a company's people count and its engagement counts describe the same set.");
        declare(entries, "DealMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The shared `visiblePersonJoin` and `visibleCompanyJoin` fragments exclude archived "
                + "endpoints from every deal person and company projection, and the company facet "
                + "buckets a deal whose company is archived as unassigned so the facet still sums to "
                + "the deal total.");
        declare(entries, "SegmentMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Segment membership feeds campaign audiences, so archived contacts and companies must "
                + "never be selected by a predicate or field match.");
        declare(entries, "CampaignMapper", ArchiveStrategy.DETECT_ARCHIVED,
            "Audience validation reports an archived contact through the same restricted-id probe as a "
                + "suspended one, and CampaignService excludes every reported id from the send.");
        declare(entries, "PersonEdgeMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The shared source/target/company visibility fragments exclude archived endpoints from the "
                + "relationship graph.");
        declare(entries, "IntroductionMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Intro candidates, warm paths, and recorded lineage all exclude archived endpoints so an "
                + "archived contact is never offered as an introduction.");
        declare(entries, "NotificationMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The shared `visiblePersonJoin` excludes archived contacts from reminder and nudge "
                + "candidates so archiving stops new notifications; already-delivered inbox history "
                + "stays readable because its subject probes only test existence and carry a stored "
                + "label and id, never a live name.");
        declare(entries, "ShareMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "New share grants are refused for archived records; revocation and listing stay "
                + "archive-agnostic so existing grants on an archived record remain revocable.");
        declare(entries, "TagMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Tag-name projections exclude archived contacts and companies, matching the lists they "
                + "decorate.");
        declare(entries, "ReportMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Closed-period aggregates are analytics over what actually happened, so excluding archived "
                + "records would retroactively rewrite settled figures; each such statement is "
                + "exempted individually. The live worklists are not covered by that argument and are "
                + "not exempted: coverage gaps, single-threaded deals, and network account values all "
                + "exclude archived records, because they name accounts and contacts the team is "
                + "asked to act on now.");
        declare(entries, "DataSubjectDisclosureMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Lawful APPI disclosure must reach retained data whatever its list visibility; an archive "
                + "may never narrow a data subject's disclosure.");
        declare(entries, "TenantLifecycleMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Tenant export, teardown, and residual verification enumerate every stored object; skipping "
                + "archived records would strand data and break the residual scan.");
        declare(entries, "LegacyTenantUploadMigrationMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Offline storage reconciliation must include archived owners so their binaries are not "
                + "stranded.");
        declare(entries, "IdentityMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Canonical identity rows remain stored for reversibility, while every parent read used "
                + "for backfill, active matching, preflight, or identity maintenance excludes "
                + "archived contacts and companies.");
        declare(entries, "IdentityCollisionMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The collision report is an actionable dedupe worklist that names each member, so archived "
                + "records drop out of it exactly as suspended and provision-ceased ones do. The keys "
                + "themselves remain retained, and a restored duplicate returns to the collision "
                + "report instead of being silently attached to one record.");
        declare(entries, "ActivityMapper", ArchiveStrategy.NO_RECORD_PROJECTION,
            "Activity reads join contacts only to resolve which activities belong to a company, so "
                + "the company timeline excludes rows linked to archived contacts while direct "
                + "association reads remain reachable for compliance and erasure.");
        declare(entries, "NoteMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Retained note associations remain reachable for compliance and erasure; the only reads "
                + "that touch a contact's name are the two note searches, and they exclude archived "
                + "contacts so a note stops being findable by an archived person's name.");
        declare(entries, "TaskMapper", ArchiveStrategy.NO_RECORD_PROJECTION,
            "Task reads join contacts only to resolve which tasks belong to a company, so retained "
                + "associations stay reachable for compliance and erasure while the company timeline "
                + "excludes rows linked to archived contacts.");
        declare(entries, "AttachmentMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The owner-label joins that back the file browser exclude archived contacts and companies, "
                + "so an archived owner's name never becomes a file's chip. Retention, quota, and "
                + "erasure read the attachment row directly without those joins and still reach every "
                + "object owned by an archived record.");
        declare(entries, "PersonEmploymentMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Employment history is the row set the archive exists to preserve, and the history read is "
                + "keyed by an already-authorised contact. The recent-moves feed projects the "
                + "contact's own name and picture and is returned verbatim by EmploymentService, so "
                + "its join carries the archive predicate itself.");
        declare(entries, "PersonLifecyclePassMapper", ArchiveStrategy.NO_RECORD_PROJECTION,
            "Lifecycle passes join the contact only to resolve its key and copy the pass's own "
                + "response timestamps, and project no identifying person column. Archiving a "
                + "contact must not rewrite the lifecycle history already recorded against it, so "
                + "the pass rows themselves stay reachable for reporting and teardown.");
        declare(entries, "ProviderCaptureMapper", ArchiveStrategy.NO_RECORD_PROJECTION,
            "Provider capture checks the retained person row only for processing restrictions and "
                + "never projects identifying person or company fields; participant evidence stays "
                + "reachable for lawful disclosure while restricted records remain ineligible for "
                + "new provider-owned activity projections.");
        declare(entries, "AiAssistantIdentifierMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Ask Connex identifier resolution projects contact and company names into prompt metadata, "
                + "so every matching record read excludes archived rows before projection.");
        return Map.copyOf(entries);
    }

    private static List<StatementExemption> buildExemptions() {
        List<StatementExemption> entries = new ArrayList<>();
        exempt(entries, "PersonMapper", "getOwnedPersonByIdForUpdate", List.of("p"),
            ExemptionReason.RECORD_LIFECYCLE_LOCK,
            "The row lock every contact write takes first. It has to see the archived row so restore "
                + "can lock it and so an update against an archived contact fails in the service "
                + "rather than silently finding nothing.");
        exempt(entries, "CompanyMapper", "getOwnedCompanyByIdForUpdate", List.of("c"),
            ExemptionReason.RECORD_LIFECYCLE_LOCK,
            "The row lock every company write takes first. It has to see the archived row so restore "
                + "can lock it and so an update against an archived company fails in the service "
                + "rather than silently finding nothing.");
        exempt(entries, "DataSubjectDisclosureMapper", "findPerson", List.of("p", "c"),
            ExemptionReason.LAWFUL_DISCLOSURE,
            "The disclosure record for the data subject themselves, including their employer at the "
                + "time. Archiving is a list-visibility decision and may not narrow what a subject is "
                + "lawfully told about their own data.");
        exempt(entries, "DataSubjectDisclosureMapper", "findEdges", List.of("counterpart"),
            ExemptionReason.LAWFUL_DISCLOSURE,
            "Relationship edges disclosed to the subject must name the counterpart even when that "
                + "counterpart has since been archived, or the disclosed graph would be incomplete.");
        exempt(entries, "DataSubjectDisclosureMapper", "findIntroductions", List.of("person_a", "person_b"),
            ExemptionReason.LAWFUL_DISCLOSURE,
            "Recorded introductions disclosed to the subject must name both endpoints even when one "
                + "has since been archived, or the disclosed history would be incomplete.");
        exempt(entries, "TenantLifecycleMapper", "streamActiveObjectReferences", List.of("p", "c"),
            ExemptionReason.OBJECT_STORAGE_KEYS_ONLY,
            "Enumerates the stored profile pictures and logos that are still referenced. An archived "
                + "record's binary is still live storage, so omitting it would let the reaper delete "
                + "an object the restore needs.");
        exempt(entries, "TenantLifecycleMapper", "findLifecycleObjectKeysAfter", List.of("p", "c"),
            ExemptionReason.OBJECT_STORAGE_KEYS_ONLY,
            "Enumerates every object key a tenant teardown or residual scan must account for; an "
                + "archived record's binary would otherwise be stranded in the bucket.");
        exempt(entries, "LegacyTenantUploadMigrationMapper", "findPersonImages", List.of("person"),
            ExemptionReason.OBJECT_STORAGE_KEYS_ONLY,
            "Migrates legacy contact-picture paths to managed storage. Skipping archived contacts "
                + "would leave their pictures unreachable after the restore.");
        exempt(entries, "LegacyTenantUploadMigrationMapper", "findCompanyImages", List.of("company"),
            ExemptionReason.OBJECT_STORAGE_KEYS_ONLY,
            "Migrates legacy company-logo paths to managed storage. Skipping archived companies would "
                + "leave their logos unreachable after the restore.");
        exempt(entries, "ReportMapper", "aggregateDeals", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Deal figures grouped by the account they closed against, over a settled period. Dropping "
                + "an account archived after the fact would change a number that was already "
                + "reported.");
        exempt(entries, "ReportMapper", "aggregateForecast", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Forecast figures grouped by account across an explicit date window; the window, not the "
                + "current list visibility, decides which accounts belong in it.");
        exempt(entries, "ReportMapper", "aggregateDealDiscount", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Effective line-item discount over the won or expected-close cohort of a period, grouped "
                + "by the account the concession was made to. Archiving the account later must not "
                + "move a discount figure that was already reported.");
        exempt(entries, "ReportMapper", "aggregateDocuments", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Counts quotes generated in the period against the account they were issued to; dropping "
                + "the accounts archived since would make the same period report different volumes "
                + "over time.");
        exempt(entries, "ReportMapper", "aggregateDocumentOutcomes", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Conversion of deals whose first document fell in the period, grouped by account. The "
                + "cohort is fixed by when the document was generated, not by which accounts are "
                + "still on the live lists.");
        exempt(entries, "ReportMapper", "aggregateDocumentApprovals", List.of("c"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Approval decisions settled in the period, grouped by account. A decision that happened "
                + "belongs to that period's turnaround whether or not the account has since been "
                + "archived.");
        exempt(entries, "ReportMapper", "aggregatePeople", List.of("company"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Counts contacts created in the period against the employer they were created under, so "
                + "the composition of a past period stays stable as records are archived.");
        exempt(entries, "ReportMapper", "aggregateEmployment", List.of("person"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Counts employment changes recorded in the period; a move that happened is part of that "
                + "period's history whether or not the contact has since been archived.");
        exempt(entries, "ReportMapper", "aggregateCompanies", List.of("company"),
            ExemptionReason.CLOSED_PERIOD_ANALYTICS,
            "Counts companies created in the period; excluding the ones archived since would make the "
                + "same report return different totals over time.");
        return List.copyOf(entries);
    }

    private static void declare(
            Map<String, ArchiveDisposition> entries,
            String mapper,
            ArchiveStrategy strategy,
            String rationale) {
        String namespace = MAPPERS + mapper;
        entries.put(namespace, new ArchiveDisposition(namespace, strategy, rationale));
    }

    private static void exempt(
            List<StatementExemption> entries,
            String mapper,
            String statementId,
            List<String> aliases,
            ExemptionReason reason,
            String rationale) {
        entries.add(new StatementExemption(
            MAPPERS + mapper, statementId, Set.copyOf(aliases), reason, rationale));
    }
}

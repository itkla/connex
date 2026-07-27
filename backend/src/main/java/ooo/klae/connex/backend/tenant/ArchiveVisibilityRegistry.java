package ooo.klae.connex.backend.tenant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reviewed archive-visibility dispositions for every mapper namespace that reads
 * {@code person} or {@code company} rows.
 *
 * <p>Archiving replaced the hard delete for those two record types (#854): the row survives, so a
 * statement that could never observe a deleted record must now carry
 * {@code archived_at IS NULL} to keep the same guarantee. This registry is the closed record of
 * that decision, one entry per namespace, each with a rationale — the same shape as
 * {@link ProcessingRestrictionRegistry}.
 *
 * <p>It is deliberately a second registry rather than a strategy added to the APPI one. The
 * concerns are orthogonal (a namespace needs a restriction disposition <em>and</em> an archive
 * disposition), and — unlike processing restrictions, which only apply to natural persons — the
 * archive predicate applies to companies too, so this registry covers company readers that have no
 * APPI counterpart.
 *
 * <p>{@code ArchiveVisibilityArchTest} fails the build when a person- or company-reading mapper has
 * no disposition here, so a future mapper that forgets the predicate is a red build rather than a
 * silent leak of archived records back into the product.
 */
public final class ArchiveVisibilityRegistry {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private static final Map<String, ArchiveDisposition> DISPOSITIONS = buildDispositions();

    private ArchiveVisibilityRegistry() {
    }

    /** Returns the immutable archive-visibility disposition map keyed by mapper namespace. */
    public static Map<String, ArchiveDisposition> dispositions() {
        return DISPOSITIONS;
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
         * The namespace deliberately reaches archived rows because retention, compliance, export,
         * teardown, or lawful disclosure must not be narrowed by an archive. No SQL evidence is
         * required; the rationale carries the justification.
         */
        REACH_ARCHIVED
    }

    /** One namespace's archive strategy and review rationale. */
    public record ArchiveDisposition(
            String mapperNamespace,
            ArchiveStrategy strategy,
            String rationale) {

        public ArchiveDisposition {
            if (mapperNamespace == null || mapperNamespace.isBlank()
                    || strategy == null || rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("Archive disposition is incomplete");
            }
        }
    }

    private static Map<String, ArchiveDisposition> buildDispositions() {
        Map<String, ArchiveDisposition> entries = new LinkedHashMap<>();
        declare(entries, "PersonMapper", ArchiveStrategy.ARCHIVE_TOGGLE,
            "The shared `visible` fragment excludes archived contacts from every ordinary read; only "
                + "the browser page/count/ids statements opt into the archived set so it can be reviewed "
                + "and restored.");
        declare(entries, "CompanyMapper", ArchiveStrategy.ARCHIVE_TOGGLE,
            "The shared `visible` fragment excludes archived companies from every ordinary read; only "
                + "the browser page/count/ids statements opt into the archived set. `visiblePerson` "
                + "additionally excludes archived contacts from company person projections.");
        declare(entries, "DealMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "The shared `visiblePersonJoin` and `visibleCompanyJoin` fragments exclude archived "
                + "endpoints from every deal person and company projection.");
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
                + "stays readable because it carries only a stored label and id.");
        declare(entries, "ShareMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "New share grants are refused for archived records; revocation and listing stay "
                + "archive-agnostic so existing grants on an archived record remain revocable.");
        declare(entries, "TagMapper", ArchiveStrategy.EXCLUDE_ARCHIVED,
            "Tag-name projections exclude archived contacts and companies, matching the lists they "
                + "decorate.");
        declare(entries, "ReportMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Reports are period analytics over what actually happened; excluding archived records "
                + "would retroactively rewrite closed-period figures, so archiving is not erasure here. "
                + "Reviewed follow-up: surfacing an archived badge in report detail is tracked by #854.");
        declare(entries, "DataSubjectDisclosureMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Lawful APPI disclosure must reach retained data whatever its list visibility; an archive "
                + "may never narrow a data subject's disclosure.");
        declare(entries, "TenantLifecycleMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Tenant export, teardown, and residual verification enumerate every row; skipping archived "
                + "records would strand data and break the residual scan.");
        declare(entries, "LegacyTenantUploadMigrationMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Offline storage reconciliation must include archived owners so their binaries are not "
                + "stranded.");
        declare(entries, "IdentityMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Canonical identity keys stay claimed while a record is archived: hiding them would let a "
                + "re-import mint a second identity for the same person and make the archive "
                + "unrestorable without a collision.");
        declare(entries, "IdentityCollisionMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Collision reporting mirrors IdentityMapper's retained keys; a group that omitted its "
                + "archived member would misreport the collision.");
        declare(entries, "ActivityMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Retained activity associations remain reachable for compliance and erasure operations, "
                + "exactly as they are for APPI-restricted contacts.");
        declare(entries, "NoteMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Retained note associations remain reachable for compliance and erasure operations.");
        declare(entries, "TaskMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Retained task associations remain reachable for compliance and erasure operations.");
        declare(entries, "AttachmentMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Retention and erasure must reach attachments owned by archived records so no object is "
                + "orphaned in storage.");
        declare(entries, "PersonEmploymentMapper", ArchiveStrategy.REACH_ARCHIVED,
            "Employment history is the row set the archive exists to preserve; the recent-moves feed is "
                + "filtered by the archive-aware person reads that hydrate it.");
        return Map.copyOf(entries);
    }

    private static void declare(
            Map<String, ArchiveDisposition> entries,
            String mapper,
            ArchiveStrategy strategy,
            String rationale) {
        String namespace = MAPPERS + mapper;
        entries.put(namespace, new ArchiveDisposition(namespace, strategy, rationale));
    }
}

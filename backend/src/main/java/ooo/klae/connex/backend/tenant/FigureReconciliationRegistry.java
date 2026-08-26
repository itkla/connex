package ooo.klae.connex.backend.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reviewed SQL declarations for revenue figures that appear in the deal browser, pipeline board,
 * home dashboard, and reports.
 *
 * <p>The registry makes reconciliation differences explicit instead of implying that similarly
 * named figures share one cohort. In particular, the filtered browser buckets deals belonging to
 * archived companies as unassigned while settled-period reports retain the archived account name;
 * reports do not traverse share joins; report open pipeline is expected-close bounded while browser
 * and home open pipeline are unbounded; and owner grouping uses the current owner, so reassignment
 * retroactively rewrites history pending the 1.1 follow-up.
 *
 * <p>{@code FigureReconciliationArchTest} resolves the named mapper statements and their included
 * fragments, then re-derives every declaration from executable SQL rather than trusting this prose.
 */
public final class FigureReconciliationRegistry {

    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private static final int MIN_RATIONALE_LENGTH = 40;
    private static final Map<Figure, FigureDefinition> DEFINITIONS = buildDefinitions();

    private FigureReconciliationRegistry() {
    }

    /** Returns the immutable figure-definition map keyed by declared figure. */
    public static Map<Figure, FigureDefinition> definitions() {
        return DEFINITIONS;
    }

    /** Revenue figures whose reconciliation contract is pinned to mapper-statement evidence. */
    public enum Figure {
        /**
         * Evidence: {@code DealMapper#dealMetricsFiltered} reads {@code d.value} and
         * {@code d.actual_value}, resolves the archived-company and share-aware visibility joins,
         * enforces person-processing restrictions when contact-filtered, has no period parameter,
         * and resolves the current-owner member-scope predicate.
         */
        DEAL_BROWSER_METRICS,
        /**
         * Evidence: {@code DealMapper#dealMetrics} reads {@code d.value} and
         * {@code d.actual_value} through {@code dealFilterWhere}, with no archive join, share join,
         * period parameter, or member-scope predicate.
         */
        DEAL_BROWSER_METRICS_UNSCOPED,
        /**
         * Evidence: {@code DealMapper#getDealBoard} returns raw deal rows including
         * {@code d.value}, constrained only by workspace and pipeline, with no archive, share,
         * period, or member-scope predicate.
         */
        PIPELINE_BOARD,
        /**
         * Evidence: {@code DealMapper#dealPipelineValue} and
         * {@code DealMapper#dealPipelineValueWindow} use {@code d.actual_value} for bounded won
         * branches and {@code d.value} for unbounded open branches under member scope.
         */
        HOME_PIPELINE_CHART,
        /**
         * Evidence: the {@code won_revenue} branch of {@code ReportMapper#aggregateDeals} sums
         * {@code d.actual_value}, requires won rows and half-open {@code closed_at} UTC bounds,
         * retains the bare-joined company label, and applies no share or member-scope join.
         */
        REPORT_WON_REVENUE,
        /**
         * Evidence: the {@code open_pipeline_value} branch of {@code ReportMapper#aggregateDeals}
         * sums {@code d.value}, requires open rows and half-open expected-close date bounds,
         * retains the bare-joined company label, and applies no share or member-scope join.
         */
        REPORT_OPEN_PIPELINE_VALUE,
        /**
         * Evidence: {@code ReportMapper#aggregateForecast} sums
         * {@code GREATEST(d.value, 0)} multiplied by a win-rate expression for a half-open,
         * expected-close-bounded open cohort while its stage conversion histories are unbounded.
         */
        REPORT_FORECAST_WEIGHTED
    }

    /** Which deal value expression contributes to a figure. */
    public enum ValueSource {
        /** Evidence: the resolved statement references canonical {@code d.value}. */
        CANONICAL_DEAL_VALUE,
        /** Evidence: the resolved statement references realized {@code d.actual_value}. */
        ACTUAL_DEAL_VALUE,
        /** Evidence: the resolved statement references both {@code d.value} and {@code d.actual_value}. */
        CANONICAL_AND_ACTUAL_DEAL_VALUES,
        /**
         * Evidence: the resolved statement multiplies {@code GREATEST(d.value, 0)} by a resolved
         * win-rate expression.
         */
        CLAMPED_CANONICAL_VALUE_TIMES_WIN_RATE
    }

    /** How archived company rows affect the figure's account dimension. */
    public enum ArchivePosture {
        /**
         * Evidence: a left company join contains {@code c.archived_at IS NULL}, and the resolved
         * filter can identify the resulting {@code c.id IS NULL} unassigned bucket.
         */
        ARCHIVED_COMPANY_AS_UNASSIGNED,
        /**
         * Evidence: the statement projects {@code c.name} through a workspace-scoped company join
         * that deliberately contains no {@code c.archived_at IS NULL} predicate.
         */
        RETAIN_ARCHIVED_COMPANY_LABEL,
        /** Evidence: the resolved statement contains no company archive predicate. */
        NO_ARCHIVE_PREDICATE
    }

    /** How APPI person-processing restrictions participate in the deal-based figure. */
    public enum RestrictionPosture {
        /**
         * Evidence: the optional contact filter joins {@code person} and requires
         * {@code archived_at IS NULL}, {@code suspended_at IS NULL}, and
         * {@code provision_ceased_at IS NULL}.
         */
        CONTACT_FILTER_EXCLUDES_UNAVAILABLE_PERSONS,
        /**
         * Evidence: the resolved statement neither joins {@code person} nor tests the person
         * restriction columns {@code suspended_at} or {@code provision_ceased_at}.
         */
        NO_PERSON_RESTRICTION_PREDICATE
    }

    /** Whether the figure traverses cross-workspace record shares. */
    public enum SharingPosture {
        /**
         * Evidence: the resolved statement contains {@code company_share}, {@code person_share},
         * and {@code pipeline_share}; stage visibility is inherited through the pipeline share
         * path, while person shares participate only when the contact filter is active.
         */
        COMPANY_PERSON_AND_PIPELINE_SHARE_TRAVERSAL,
        /** Evidence: the resolved statement contains no table whose name ends in {@code _share}. */
        NO_SHARE_TRAVERSAL
    }

    /** Which event date, if any, bounds the figure's deal cohort. */
    public enum PeriodBasis {
        /** Evidence: the resolved statement contains none of the reviewed period parameters. */
        UNBOUNDED,
        /**
         * Evidence: the won CASE branch bounds {@code d.closed_at} while the open CASE branch sums
         * {@code d.value} without a date predicate.
         */
        WON_CLOSED_AT_BOUNDED_OPEN_UNBOUNDED,
        /**
         * Evidence: the statement contains {@code query.startUtc} and {@code query.endUtc} for the
         * half-open {@code d.closed_at} report window.
         */
        CLOSED_AT_UTC_HALF_OPEN,
        /**
         * Evidence: the statement contains {@code query.startDate} and
         * {@code query.endDateExclusive} for a half-open {@code d.expected_close_date} window.
         */
        EXPECTED_CLOSE_DATE_HALF_OPEN,
        /**
         * Evidence: the open cohort has one half-open expected-close window, while the legacy-stage,
         * reached-stage, and workspace-history inputs contain no report-period parameters.
         */
        EXPECTED_CLOSE_DATE_HALF_OPEN_WITH_ALL_HISTORY
    }

    /**
     * How the current deal owner affects a figure.
     *
     * <p>Member scope is a presentational filter, not an authorization boundary: any workspace
     * member may select any member scope, and {@code REPORT_READ} is a base member permission.
     * These declarations are reconciliation claims and must never be read as confidentiality
     * guarantees. Every owner reference is current {@code d.owner_id}; reassignment therefore
     * retroactively rewrites owner-grouped history pending the 1.1 follow-up.
     */
    public enum OwnerBasis {
        /**
         * Evidence: the resolved statement contains a {@code memberScope} parameter and a
         * {@code d.owner_id} predicate.
         */
        MEMBER_SCOPE_ON_CURRENT_OWNER,
        /** Evidence: the resolved statement contains no {@code memberScope} parameter. */
        NO_MEMBER_SCOPE,
        /**
         * Evidence: the report statement groups through current {@code d.owner_id}, contains its
         * optional owner-id predicate, and contains no {@code memberScope} parameter.
         */
        CURRENT_OWNER_GROUPING_AND_OPTIONAL_FILTER
    }

    /** One revenue figure's reviewed SQL evidence and reconciliation postures. */
    public record FigureDefinition(
            Figure figure,
            List<StatementEvidence> evidence,
            ValueSource valueSource,
            ArchivePosture archivePosture,
            RestrictionPosture restrictionPosture,
            SharingPosture sharingPosture,
            PeriodBasis periodBasis,
            OwnerBasis ownerBasis,
            String rationale) {

        public FigureDefinition {
            if (figure == null || evidence == null || evidence.isEmpty()
                    || evidence.stream().anyMatch(statement -> statement == null)
                    || valueSource == null || archivePosture == null || restrictionPosture == null
                    || sharingPosture == null || periodBasis == null || ownerBasis == null
                    || rationale == null || rationale.length() < MIN_RATIONALE_LENGTH) {
                throw new IllegalArgumentException("Figure definition is incomplete");
            }
            evidence = List.copyOf(evidence);
        }
    }

    /** One mapper statement whose resolved SQL is evidence for a figure definition. */
    public record StatementEvidence(String mapperNamespace, String statementId) {

        public StatementEvidence {
            if (mapperNamespace == null || mapperNamespace.isBlank()
                    || statementId == null || statementId.isBlank()) {
                throw new IllegalArgumentException("Figure statement evidence is incomplete");
            }
        }

        /** Returns the {@code namespace#statementId} key this evidence names. */
        public String key() {
            return mapperNamespace + "#" + statementId;
        }
    }

    private static Map<Figure, FigureDefinition> buildDefinitions() {
        Map<Figure, FigureDefinition> entries = new LinkedHashMap<>();
        declare(entries, Figure.DEAL_BROWSER_METRICS,
            List.of(evidence("DealMapper", "dealMetricsFiltered")),
            ValueSource.CANONICAL_AND_ACTUAL_DEAL_VALUES,
            ArchivePosture.ARCHIVED_COMPANY_AS_UNASSIGNED,
            RestrictionPosture.CONTACT_FILTER_EXCLUDES_UNAVAILABLE_PERSONS,
            SharingPosture.COMPANY_PERSON_AND_PIPELINE_SHARE_TRAVERSAL,
            PeriodBasis.UNBOUNDED,
            OwnerBasis.MEMBER_SCOPE_ON_CURRENT_OWNER,
            "The filtered browser uses canonical open value and realized closed revenue, buckets an "
                + "archived company's deals as unassigned, traverses company, pipeline, stage, and "
                + "contact visibility shares, excludes restricted contacts from contact-filtered "
                + "figures, remains unbounded, and filters on the current owner.");
        declare(entries, Figure.DEAL_BROWSER_METRICS_UNSCOPED,
            List.of(evidence("DealMapper", "dealMetrics")),
            ValueSource.CANONICAL_AND_ACTUAL_DEAL_VALUES,
            ArchivePosture.NO_ARCHIVE_PREDICATE,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.UNBOUNDED,
            OwnerBasis.NO_MEMBER_SCOPE,
            "The unfiltered browser variant is the deliberate flagship divergence: dealFilterWhere "
                + "joins nothing, so archived deals remain, no record shares are traversed, and no "
                + "member scope narrows its unbounded canonical and realized totals.");
        declare(entries, Figure.PIPELINE_BOARD,
            List.of(evidence("DealMapper", "getDealBoard")),
            ValueSource.CANONICAL_DEAL_VALUE,
            ArchivePosture.NO_ARCHIVE_PREDICATE,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.UNBOUNDED,
            OwnerBasis.NO_MEMBER_SCOPE,
            "The pipeline board returns workspace-and-pipeline-scoped raw deal rows using canonical "
                + "deal value, with no archive predicate, share traversal, date bound, or member "
                + "scope applied to the current deal rows.");
        declare(entries, Figure.HOME_PIPELINE_CHART,
            List.of(
                evidence("DealMapper", "dealPipelineValue"),
                evidence("DealMapper", "dealPipelineValueWindow")),
            ValueSource.CANONICAL_AND_ACTUAL_DEAL_VALUES,
            ArchivePosture.NO_ARCHIVE_PREDICATE,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.WON_CLOSED_AT_BOUNDED_OPEN_UNBOUNDED,
            OwnerBasis.MEMBER_SCOPE_ON_CURRENT_OWNER,
            "The home chart deliberately mixes a rolling or half-open closed-at won cohort using "
                + "actual value with an unbounded open cohort using canonical value. Its current-owner "
                + "member scope is presentational, and unlike reports it traverses no shares.");
        declare(entries, Figure.REPORT_WON_REVENUE,
            List.of(evidence("ReportMapper", "aggregateDeals")),
            ValueSource.ACTUAL_DEAL_VALUE,
            ArchivePosture.RETAIN_ARCHIVED_COMPANY_LABEL,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.CLOSED_AT_UTC_HALF_OPEN,
            OwnerBasis.CURRENT_OWNER_GROUPING_AND_OPTIONAL_FILTER,
            "Won revenue sums realized value for won deals in a half-open closed-at UTC period. The "
                + "bare company join retains archived account names for settled-period reporting, "
                + "reports traverse no shares, and current-owner grouping rewrites after reassignment.");
        declare(entries, Figure.REPORT_OPEN_PIPELINE_VALUE,
            List.of(evidence("ReportMapper", "aggregateDeals")),
            ValueSource.CANONICAL_DEAL_VALUE,
            ArchivePosture.RETAIN_ARCHIVED_COMPANY_LABEL,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.EXPECTED_CLOSE_DATE_HALF_OPEN,
            OwnerBasis.CURRENT_OWNER_GROUPING_AND_OPTIONAL_FILTER,
            "Report open pipeline sums canonical value for open deals in a half-open expected-close "
                + "window, unlike the unbounded browser and home open totals. It retains archived "
                + "account names, traverses no shares, and groups by the current owner.");
        declare(entries, Figure.REPORT_FORECAST_WEIGHTED,
            List.of(evidence("ReportMapper", "aggregateForecast")),
            ValueSource.CLAMPED_CANONICAL_VALUE_TIMES_WIN_RATE,
            ArchivePosture.RETAIN_ARCHIVED_COMPANY_LABEL,
            RestrictionPosture.NO_PERSON_RESTRICTION_PREDICATE,
            SharingPosture.NO_SHARE_TRAVERSAL,
            PeriodBasis.EXPECTED_CLOSE_DATE_HALF_OPEN_WITH_ALL_HISTORY,
            OwnerBasis.CURRENT_OWNER_GROUPING_AND_OPTIONAL_FILTER,
            "Weighted forecast clamps negative canonical values before applying the win rate and "
                + "bounds the open cohort by expected close date, while all stage conversion history "
                + "remains unbounded. Reports retain archived account names and traverse no shares.");
        return Map.copyOf(entries);
    }

    private static StatementEvidence evidence(String mapper, String statementId) {
        return new StatementEvidence(MAPPERS + mapper, statementId);
    }

    private static void declare(
            Map<Figure, FigureDefinition> entries,
            Figure figure,
            List<StatementEvidence> evidence,
            ValueSource valueSource,
            ArchivePosture archivePosture,
            RestrictionPosture restrictionPosture,
            SharingPosture sharingPosture,
            PeriodBasis periodBasis,
            OwnerBasis ownerBasis,
            String rationale) {
        FigureDefinition previous = entries.put(figure, new FigureDefinition(
            figure, evidence, valueSource, archivePosture, restrictionPosture, sharingPosture,
            periodBasis, ownerBasis, rationale));
        if (previous != null) {
            throw new IllegalArgumentException("Figure is declared more than once: " + figure);
        }
    }
}

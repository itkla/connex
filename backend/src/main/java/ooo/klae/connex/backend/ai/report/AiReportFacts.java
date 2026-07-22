package ooo.klae.connex.backend.ai.report;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.dto.ReportAppendixRowDto;

/**
 * Produces the only narrative clauses and headings that report AI output may select.
 */
final class AiReportFacts {
    private static final List<String> ENGLISH_TITLES = List.of(
            "Executive summary", "Performance commentary", "Key findings");
    private static final List<String> JAPANESE_TITLES = List.of(
            "エグゼクティブサマリー", "パフォーマンス分析", "主な所見");
    private static final Set<String> WHOLE_METRIC_GROUPS = Set.of(
            "total", "workspace-wide", "unspecified", "unassigned");
    private static final Map<String, String> ENGLISH_LABELS = Map.ofEntries(
            Map.entry("count", "Count"),
            Map.entry("new_pipeline_value", "New pipeline value"),
            Map.entry("won_revenue", "Won revenue"),
            Map.entry("attainment", "Quota attainment"),
            Map.entry("win_rate", "Win rate"),
            Map.entry("avg_cycle_days", "Average cycle days"),
            Map.entry("open_pipeline_value", "Open pipeline value"),
            Map.entry("open_deal_count", "Open deal count"),
            Map.entry("at_risk_revenue", "At-risk revenue"),
            Map.entry("company_count", "Company count"),
            Map.entry("coverage_gap_count", "Coverage gap count"),
            Map.entry("coverage_gap_open_pipeline_value", "Coverage-gap open pipeline value"),
            Map.entry("single_threaded_deal_count", "Single-threaded deal count"),
            Map.entry("single_threaded_deal_value", "Single-threaded deal value"),
            Map.entry("warm_intro_opportunity_value", "Warm-intro opportunity value"),
            Map.entry("warm_intro_reachable_account_count", "Warm-intro reachable account count"),
            Map.entry("reverse_intro_weighted_opportunities", "Weighted reverse-intro opportunities"),
            Map.entry("employment_departure_count", "Employment departures"),
            Map.entry("employment_arrival_count", "Employment arrivals"),
            Map.entry("forecast_best", "Best-case forecast"),
            Map.entry("forecast_weighted", "Likely forecast (weighted)"),
            Map.entry("forecast_worst", "Commit forecast"),
            Map.entry("total", "Total"),
            Map.entry("workspace-wide", "Workspace-wide"),
            Map.entry("unassigned", "Unassigned"),
            Map.entry("unspecified", "Unspecified"),
            Map.entry("open", "Open"),
            Map.entry("won", "Won"),
            Map.entry("lost", "Lost"),
            Map.entry("todo", "To do"),
            Map.entry("in progress", "In progress"),
            Map.entry("done", "Done"),
            Map.entry("hot", "Hot"),
            Map.entry("warm", "Warm"),
            Map.entry("cool", "Cool"),
            Map.entry("cold", "Cold"),
            Map.entry("high", "High"),
            Map.entry("medium", "Medium"),
            Map.entry("low", "Low"),
            Map.entry("rising", "Rising"),
            Map.entry("steady", "Steady"),
            Map.entry("cooling", "Cooling"));
    private static final Map<String, String> JAPANESE_LABELS = Map.ofEntries(
            Map.entry("count", "件数"),
            Map.entry("new_pipeline_value", "新規パイプライン金額"),
            Map.entry("won_revenue", "受注売上"),
            Map.entry("attainment", "目標達成率"),
            Map.entry("win_rate", "受注率"),
            Map.entry("avg_cycle_days", "平均商談日数"),
            Map.entry("open_pipeline_value", "進行中パイプライン金額"),
            Map.entry("open_deal_count", "進行中商談数"),
            Map.entry("at_risk_revenue", "リスク売上"),
            Map.entry("company_count", "会社数"),
            Map.entry("coverage_gap_count", "カバレッジ不足の会社数"),
            Map.entry("coverage_gap_open_pipeline_value", "カバレッジ不足の進行中パイプライン金額"),
            Map.entry("single_threaded_deal_count", "単一接点案件数"),
            Map.entry("single_threaded_deal_value", "単一接点案件金額"),
            Map.entry("warm_intro_opportunity_value", "ウォーム紹介の機会価値"),
            Map.entry("warm_intro_reachable_account_count", "ウォーム紹介で到達可能なアカウント数"),
            Map.entry("reverse_intro_weighted_opportunities", "加重リバース紹介機会"),
            Map.entry("employment_departure_count", "勤務先からの離職数"),
            Map.entry("employment_arrival_count", "勤務先への入社数"),
            Map.entry("forecast_best", "最良ケース予測"),
            Map.entry("forecast_weighted", "見込み予測（加重）"),
            Map.entry("forecast_worst", "コミット予測"),
            Map.entry("total", "合計"),
            Map.entry("workspace-wide", "ワークスペース全体"),
            Map.entry("unassigned", "未割り当て"),
            Map.entry("unspecified", "未指定"),
            Map.entry("open", "進行中"),
            Map.entry("won", "受注"),
            Map.entry("lost", "失注"),
            Map.entry("todo", "未着手"),
            Map.entry("in progress", "進行中"),
            Map.entry("done", "完了"),
            Map.entry("hot", "非常に良好"),
            Map.entry("warm", "良好"),
            Map.entry("cool", "低下傾向"),
            Map.entry("cold", "要注意"),
            Map.entry("high", "高"),
            Map.entry("medium", "中"),
            Map.entry("low", "低"),
            Map.entry("rising", "上昇"),
            Map.entry("steady", "安定"),
            Map.entry("cooling", "低下"));

    private AiReportFacts() {
    }

    static List<String> titles() {
        return japanese() ? JAPANESE_TITLES : ENGLISH_TITLES;
    }

    static Set<String> titleSet() {
        return Set.copyOf(titles());
    }

    /**
     * The localized measure name for a source, derived from the report widget's measure enum (never
     * tenant data), safe to send to the provider in the clear as prose context.
     */
    static String measureLabel(ReportAppendixRowDto source) {
        String measure = measure(source);
        Map<String, String> labels = japanese() ? JAPANESE_LABELS : ENGLISH_LABELS;
        return labels.getOrDefault(measure, measure);
    }

    /** Whether a source is grouped by a distinct value rather than a whole-metric total. */
    static boolean hasDistinctGroup(ReportAppendixRowDto source) {
        String group = rawGroup(source);
        return group != null && !WHOLE_METRIC_GROUPS.contains(group.toLowerCase(Locale.ROOT));
    }

    /** The localized group value of a source, masked by the caller as tenant data. */
    static String groupSegment(ReportAppendixRowDto source) {
        String group = rawGroup(source);
        if (group == null) {
            return null;
        }
        Map<String, String> labels = japanese() ? JAPANESE_LABELS : ENGLISH_LABELS;
        return labels.getOrDefault(group.toLowerCase(Locale.ROOT), group);
    }

    private static String rawGroup(ReportAppendixRowDto source) {
        String[] parts = source.label().split(" · ", 2);
        return parts.length < 2 || parts[1].isBlank() ? null : parts[1];
    }

    private static String measure(ReportAppendixRowDto source) {
        return source.label().split(" · ", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static boolean japanese() {
        Locale locale = LocaleContextHolder.getLocale();
        return Locale.JAPANESE.getLanguage().equals(locale.getLanguage());
    }
}

package ooo.klae.connex.backend.ai.report;

import java.math.BigDecimal;
import java.util.Arrays;
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
    private static final Map<String, String> ENGLISH_LABELS = Map.ofEntries(
            Map.entry("count", "Count"),
            Map.entry("new_pipeline_value", "New pipeline value"),
            Map.entry("won_revenue", "Won revenue"),
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
            Map.entry("forecast_best", "Best-case forecast"),
            Map.entry("forecast_weighted", "Likely forecast (weighted)"),
            Map.entry("forecast_worst", "Commit forecast"),
            Map.entry("total", "Total"),
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
            Map.entry("forecast_best", "最良ケース予測"),
            Map.entry("forecast_weighted", "見込み予測（加重）"),
            Map.entry("forecast_worst", "コミット予測"),
            Map.entry("total", "合計"),
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

    static String claim(ReportAppendixRowDto source) {
        String label = label(source);
        BigDecimal current = source.value();
        BigDecimal prior = source.priorValue();
        if (prior == null) {
            if (Set.of("forecast_best", "forecast_weighted", "forecast_worst").contains(measure(source))) {
                return japanese()
                        ? label + "は確定的に計算された将来予測です。"
                        : label + " is a deterministic forward forecast.";
            }
            return japanese()
                    ? label + "には当期の確定結果があります。"
                    : label + " has a deterministic current-period result.";
        }
        int comparison = current.compareTo(prior);
        if (japanese()) {
            if (comparison > 0) {
                return label + "は前期間より増加しました。";
            }
            if (comparison < 0) {
                return label + "は前期間より減少しました。";
            }
            return label + "は前期間と同水準でした。";
        }
        if (comparison > 0) {
            return label + " increased from the prior period.";
        }
        if (comparison < 0) {
            return label + " decreased from the prior period.";
        }
        return label + " was unchanged from the prior period.";
    }

    static List<String> claims(ReportAppendixRowDto source) {
        String label = label(source);
        String measure = measure(source);
        String recommendation;
        if (Set.of("coverage_gap_count", "coverage_gap_open_pipeline_value").contains(measure)) {
            recommendation = japanese()
                    ? label + "を確認し、追加の関係構築担当者を割り当ててください。"
                    : "Review " + label + " and assign an additional relationship owner.";
        } else if (Set.of("single_threaded_deal_count", "single_threaded_deal_value").contains(measure)) {
            recommendation = japanese()
                    ? label + "を確認し、案件に追加のステークホルダーを登録してください。"
                    : "Review " + label + " and add another stakeholder to the deal.";
        } else if (Set.of("forecast_best", "forecast_weighted", "forecast_worst").contains(measure)) {
            recommendation = japanese()
                    ? label + "を期間目標と比較し、加重予測を危うくする進行中案件を特定してください。"
                    : "Compare " + label
                            + " with the period target and identify open deals putting the weighted forecast at risk.";
        } else {
            recommendation = japanese()
                    ? label + "を担当チームで確認し、次の対応を記録してください。"
                    : "Review " + label + " with the responsible team and record the next action.";
        }
        return List.of(claim(source), recommendation);
    }

    static String label(ReportAppendixRowDto source) {
        Map<String, String> labels = japanese() ? JAPANESE_LABELS : ENGLISH_LABELS;
        return Arrays.stream(source.label().split(" · ", -1))
                .map(part -> labels.getOrDefault(part.toLowerCase(Locale.ROOT), part))
                .reduce((left, right) -> left + " · " + right)
                .orElse(source.label());
    }

    private static String measure(ReportAppendixRowDto source) {
        return source.label().split(" · ", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static boolean japanese() {
        Locale locale = LocaleContextHolder.getLocale();
        return Locale.JAPANESE.getLanguage().equals(locale.getLanguage());
    }
}

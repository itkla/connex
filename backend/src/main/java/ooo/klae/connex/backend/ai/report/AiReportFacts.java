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
    private static final Set<String> CURRENT_STATE_MEASURES = Set.of(
            "warm_intro_opportunity_value",
            "warm_intro_reachable_account_count",
            "reverse_intro_weighted_opportunities");
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

    static String claim(ReportAppendixRowDto source) {
        String label = label(source);
        BigDecimal current = source.value();
        BigDecimal prior = source.priorValue();
        if ("attainment".equals(measure(source)) && prior != null) {
            if (prior.signum() == 0) {
                return japanese()
                        ? label + "の目標はゼロのため、達成率は算出されません。"
                        : label + " has a zero target, so its attainment percentage is undefined.";
            }
            int comparison = current.compareTo(prior);
            if (japanese()) {
                if (comparison > 0) {
                    return label + "は目標を上回っています。";
                }
                if (comparison < 0) {
                    return label + "は目標を下回っています。";
                }
                return label + "は目標を達成しています。";
            }
            if (comparison > 0) {
                return label + " is ahead of quota.";
            }
            if (comparison < 0) {
                return label + " is behind quota.";
            }
            return label + " has met quota.";
        }
        if (prior == null) {
            if (CURRENT_STATE_MEASURES.contains(measure(source))) {
                return japanese()
                        ? label + "は生成時点の現在状態を示す確定的なスナップショットです。"
                        : label + " is a deterministic current-state snapshot as of generation time.";
            }
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
        return List.of(fact(source), recommendation(source));
    }

    /** The deterministic fact sentence for a source (grounding {@code kind = fact}). */
    static String fact(ReportAppendixRowDto source) {
        return claim(source);
    }

    /** The deterministic recommendation sentence for a source (grounding {@code kind = recommendation}). */
    static String recommendation(ReportAppendixRowDto source) {
        String label = label(source);
        String measure = measure(source);
        String recommendation;
        if (Set.of("coverage_gap_count", "coverage_gap_open_pipeline_value").contains(measure)) {
            recommendation = japanese()
                    ? label + "を確認し、追加の関係構築担当者を割り当ててください。"
                    : "Review " + label + " and assign an additional relationship owner.";
        } else if (Set.of("warm_intro_opportunity_value", "warm_intro_reachable_account_count")
                .contains(measure)) {
            recommendation = japanese()
                    ? label + "を優先し、適切なコネクターを割り当てて対象となる紹介経路を開始してください。"
                    : "Prioritize " + label
                            + " and assign the appropriate connector to activate a qualifying introduction path.";
        } else if ("reverse_intro_weighted_opportunities".equals(measure)) {
            recommendation = japanese()
                    ? label + "を確認し、提案された紹介を記録するか却下してください。"
                    : "Review " + label + " and record or dismiss the suggested introduction.";
        } else if (Set.of("single_threaded_deal_count", "single_threaded_deal_value").contains(measure)) {
            recommendation = japanese()
                    ? label + "を確認し、案件に追加のステークホルダーを登録してください。"
                    : "Review " + label + " and add another stakeholder to the deal.";
        } else if (Set.of("forecast_best", "forecast_weighted", "forecast_worst").contains(measure)) {
            recommendation = japanese()
                    ? label + "を期間目標と比較し、加重予測を危うくする進行中案件を特定してください。"
                    : "Compare " + label
                            + " with the period target and identify open deals putting the weighted forecast at risk.";
        } else if ("attainment".equals(measure)) {
            BigDecimal target = source.priorValue();
            if (target != null && target.signum() == 0) {
                recommendation = japanese()
                        ? label + "のゼロ目標が意図した設定か確認してください。"
                        : "Confirm whether the zero target for " + label + " is intentional.";
            } else if (target != null && source.value().compareTo(target) >= 0) {
                recommendation = japanese()
                        ? label + "の達成要因を確認し、再現可能な要因を特定してください。"
                        : "Review what put " + label + " on or ahead of quota and identify repeatable drivers.";
            } else {
                recommendation = japanese()
                        ? label + "の不足分を確認し、差を縮める案件や対応を特定してください。"
                        : "Review the gap for " + label + " and identify deals or actions that could close it.";
            }
        } else {
            recommendation = japanese()
                    ? label + "を担当チームで確認し、次の対応を記録してください。"
                    : "Review " + label + " with the responsible team and record the next action.";
        }
        return recommendation;
    }

    /**
     * The localized measure name for a source, derived from the report widget's measure enum (never
     * tenant data), safe to send to the provider in the clear as selection context.
     */
    static String measureLabel(ReportAppendixRowDto source) {
        String measure = measure(source);
        Map<String, String> labels = japanese() ? JAPANESE_LABELS : ENGLISH_LABELS;
        return labels.getOrDefault(measure, measure);
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

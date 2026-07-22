package ooo.klae.connex.backend.services;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.ReportWidgetDataDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Derives the read permissions required by saved and generated report content. */
@Component
@RequiredArgsConstructor
public class ReportPermissionPolicy {
    private static final Set<String> REPORT_READ_MEASURES = Set.of(
            "count", "new_pipeline_value", "won_revenue", "win_rate", "avg_cycle_days",
            "open_pipeline_value", "open_deal_count", "at_risk_revenue",
            "single_threaded_deal_count", "single_threaded_deal_value",
            "forecast_best", "forecast_weighted", "forecast_worst",
            "coverage_gap_count", "coverage_gap_open_pipeline_value", "company_count",
            "warm_intro_opportunity_value", "warm_intro_reachable_account_count",
            "reverse_intro_weighted_opportunities", "employment_departure_count",
            "employment_arrival_count");
    private static final Set<String> MAPPED_MEASURES = mappedMeasureCatalog();

    private final ObjectMapper objectMapper;

    /** Returns the permissions required to read a persisted report definition. */
    public Set<Permission> requiredFor(ReportDefinition definition) {
        if (definition == null || definition.getConfigJson() == null || definition.getConfigJson().isBlank()) {
            throw corruptConfiguration();
        }
        ReportConfig config;
        try {
            config = objectMapper.readValue(definition.getConfigJson(), ReportConfig.class);
        } catch (JacksonException exception) {
            throw corruptConfiguration();
        }
        validateConfiguration(definition.getCadence(), definition.getTemplateKey(), config);
        return requiredFor(config);
    }

    /** Returns the permissions required to read an exact generated report document. */
    public Set<Permission> requiredFor(ReportDocumentDto document) {
        if (document == null || document.definition() == null) {
            throw corruptConfiguration();
        }
        ReportConfig config = document.definition().config();
        validateConfiguration(
                document.definition().cadence(), document.definition().templateKey(), config);
        if (document.widgets() == null || document.widgets().size() != config.widgets().size()) {
            throw corruptConfiguration();
        }
        for (int index = 0; index < config.widgets().size(); index++) {
            ReportWidgetConfig configured = config.widgets().get(index);
            ReportWidgetDataDto generated = document.widgets().get(index);
            if (generated == null
                    || !configured.id().equals(generated.widgetId())
                    || !configured.measure().equals(generated.measure())) {
                throw corruptConfiguration();
            }
        }
        return requiredFor(config);
    }

    private static Set<Permission> requiredFor(ReportConfig config) {
        if (config == null || config.widgets() == null || config.widgets().isEmpty()
                || config.widgets().stream().anyMatch(widget ->
                        widget == null || widget.measure() == null || widget.measure().isBlank())) {
            throw corruptConfiguration();
        }
        EnumSet<Permission> required = EnumSet.of(Permission.REPORT_READ);
        for (ReportWidgetConfig widget : config.widgets()) {
            if ("attainment".equals(widget.measure())) {
                required.add(Permission.GOAL_READ);
            } else if (!REPORT_READ_MEASURES.contains(widget.measure())) {
                throw corruptConfiguration();
            }
        }
        return Set.copyOf(required);
    }

    static Set<String> mappedMeasures() {
        return MAPPED_MEASURES;
    }

    private static Set<String> mappedMeasureCatalog() {
        Set<String> measures = new HashSet<>(REPORT_READ_MEASURES);
        measures.add("attainment");
        return Set.copyOf(measures);
    }

    private static BadRequestException corruptConfiguration() {
        return new BadRequestException("Corrupt report configuration");
    }

    private static void validateConfiguration(String cadence, String templateKey, ReportConfig config) {
        try {
            ReportService.validateConfig(cadence, templateKey, config);
        } catch (BadRequestException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw corruptConfiguration();
        }
    }
}

package ooo.klae.connex.backend.integration;

import java.util.List;

/** Stateless payloads shared by report HTTP and KPI transaction-boundary tests. */
final class ReportTestFixtures {
    private ReportTestFixtures() {
    }

    static final String REPORT_BODY = """
        {
          "name": "January Activity",
          "description": "Monthly activity review",
          "cadence": "custom",
          "templateKey": null,
          "config": {
            "widgets": [{
              "id": "activity-total",
              "title": "Activity total",
              "dataSource": "activities",
              "measure": "count",
              "groupBy": "none",
              "chartType": "kpi"
            }],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": {"start": "2026-01-01", "end": "2026-01-31"},
            "bucket": "day",
            "layout": [{"widgetId": "activity-total", "x": 0, "y": 0, "width": 6, "height": 4}]
          }
        }
        """;

    static final String ATTAINMENT_BODY = """
        {
          "name": "July Quota Attainment",
          "description": "Revenue targets and actuals",
          "cadence": "monthly",
          "templateKey": "quota-attainment",
          "config": {
            "widgets": [
              {
                "id": "owner-attainment",
                "title": "Attainment by owner",
                "dataSource": "deals",
                "measure": "attainment",
                "groupBy": "owner",
                "chartType": "bar"
              },
              {
                "id": "workspace-attainment",
                "title": "Overall attainment",
                "dataSource": "deals",
                "measure": "attainment",
                "groupBy": "none",
                "chartType": "kpi"
              }
            ],
            "filters": {
              "pipelineIds": null,
              "ownerIds": null,
              "statuses": null,
              "tagIds": null,
              "warmthBands": null
            },
            "range": null,
            "bucket": "month",
            "layout": [
              {"widgetId": "owner-attainment", "x": 0, "y": 0, "width": 6, "height": 4},
              {"widgetId": "workspace-attainment", "x": 6, "y": 0, "width": 6, "height": 4}
            ]
          }
        }
        """;

    /** One widget of a generated commercial-metrics report body. */
    record CommercialWidget(
            String id, String dataSource, String measure, String groupBy, String chartType) {
    }

    static String commercialReportBody(List<CommercialWidget> widgets) {
        return commercialReportBody(
                widgets,
                "{\"pipelineIds\": null, \"ownerIds\": null, \"statuses\": null, "
                        + "\"tagIds\": null, \"warmthBands\": null}",
                "2026-01-01",
                "2026-01-31",
                "day");
    }

    static String commercialReportBody(
            List<CommercialWidget> widgets,
            String filters,
            String rangeStart,
            String rangeEnd,
            String bucket) {
        StringBuilder widgetJson = new StringBuilder();
        StringBuilder layoutJson = new StringBuilder();
        for (int index = 0; index < widgets.size(); index++) {
            CommercialWidget widget = widgets.get(index);
            if (index > 0) {
                widgetJson.append(',');
                layoutJson.append(',');
            }
            widgetJson.append(("{\"id\": \"%s\", \"title\": null, \"dataSource\": \"%s\", "
                    + "\"measure\": \"%s\", \"groupBy\": \"%s\", \"chartType\": \"%s\"}").formatted(
                    widget.id(), widget.dataSource(), widget.measure(),
                    widget.groupBy(), widget.chartType()));
            layoutJson.append(
                    "{\"widgetId\": \"%s\", \"x\": %d, \"y\": %d, \"width\": 6, \"height\": 4}".formatted(
                            widget.id(), index % 2 * 6, index / 2 * 4));
        }
        return """
            {
              "name": "Commercial documents",
              "description": "Quote, approval, and discount metrics",
              "cadence": "custom",
              "templateKey": null,
              "config": {
                "widgets": [%s],
                "filters": %s,
                "range": {"start": "%s", "end": "%s"},
                "bucket": "%s",
                "layout": [%s]
              }
            }
            """.formatted(widgetJson, filters, rangeStart, rangeEnd, bucket, layoutJson);
    }

}

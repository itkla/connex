package ooo.klae.connex.backend.dto;

import java.util.List;

/** Native record-list filters accepted by exact manual scope preparation. */
public record WorkflowManualFilter(
    String query,
    List<String> companies,
    List<String> titles,
    List<String> industry,
    Boolean noCompany,
    String currency,
    List<Integer> pipelineIds,
    List<Integer> stageIds,
    List<Integer> companyIds,
    List<String> statuses,
    List<String> risks,
    String memberScope,
    List<Integer> memberIds
) { }

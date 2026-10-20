package ooo.klae.connex.backend.support;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

/** Compares all nine Pipeline projections without imposing ordering on unordered SQL. */
public final class PipelineVisibilityParity {
    private PipelineVisibilityParity() { }

    /** Captures the frozen queries before a rewritten locking read can retain any row locks. */
    public static Map<String, Object> legacy(RecordVisibilityPlaneFixture fixture, int pipelineId, int stageId) {
        Map<String, Object> parameters = fixture.parameters();
        parameters.put("id", pipelineId);
        parameters.put("pipelineId", pipelineId);
        parameters.put("query", "%Pipeline%");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("getAllPipelines", pipelines(fixture.legacy.selectList("PipelineMapper.getAllPipelines", parameters), true));
        result.put("getPipelineById", pipeline(first(fixture.legacy.selectList("PipelineMapper.getPipelineById", parameters))));
        result.put("getVisiblePipelineByIdForUpdate", pipeline(first(fixture.legacy.selectList(
            "PipelineMapper.getVisiblePipelineByIdForUpdate", parameters))));
        result.put("search", pipelines(fixture.legacy.selectList("PipelineMapper.search", parameters), false));
        result.put("pipelineExists", first(fixture.legacy.selectList("PipelineMapper.pipelineExists", parameters)));
        result.put("getAllStages", stages(fixture.legacy.selectList("PipelineMapper.getAllStages", parameters)));
        result.put("getStagesByPipelineId", stages(fixture.legacy.selectList("PipelineMapper.getStagesByPipelineId", parameters)));
        parameters.put("id", stageId);
        result.put("getVisibleStageById", stage(first(fixture.legacy.selectList("PipelineMapper.getVisibleStageById", parameters))));
        result.put("getVisibleStageByIdForUpdate", stage(first(fixture.legacy.selectList(
            "PipelineMapper.getVisibleStageByIdForUpdate", parameters))));
        return result;
    }

    /** Executes the real rewritten mapper, including both authorization locks and nested stages. */
    public static Map<String, Object> tenant(
            RecordVisibilityPlaneFixture fixture, String scope, int pipelineId, int stageId) {
        var mapper = fixture.pipelineMapper;
        int workspaceId = fixture.workspaceId;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("getAllPipelines", pipelines(mapper.getAllPipelines(workspaceId, scope), true));
        result.put("getPipelineById", pipeline(mapper.getPipelineById(workspaceId, scope, pipelineId)));
        result.put("getVisiblePipelineByIdForUpdate", pipeline(mapper.getVisiblePipelineByIdForUpdate(workspaceId, scope, pipelineId)));
        result.put("search", pipelines(mapper.search(workspaceId, scope, "%Pipeline%"), false));
        result.put("pipelineExists", mapper.pipelineExists(workspaceId, scope, pipelineId));
        result.put("getAllStages", stages(mapper.getAllStages(workspaceId, scope)));
        result.put("getStagesByPipelineId", stages(mapper.getStagesByPipelineId(workspaceId, scope, pipelineId)));
        result.put("getVisibleStageById", stage(mapper.getVisibleStageById(workspaceId, scope, stageId)));
        result.put("getVisibleStageByIdForUpdate", stage(mapper.getVisibleStageByIdForUpdate(workspaceId, scope, stageId)));
        return result;
    }

    /** Includes the full ordered stage array, distinguishing lightweight and empty full results. */
    public static Object pipeline(Pipeline pipeline) {
        return pipeline == null ? null : Arrays.asList(pipeline.getId(), pipeline.getWorkspaceId(), pipeline.getName(),
            pipeline.getCreatedAt(), pipeline.getUpdatedAt(),
            pipeline.getStages() == null ? null : stages(Arrays.asList(pipeline.getStages())));
    }

    /** Preserves the explicit stage order and every projected stage field. */
    public static List<Object> stages(List<Stage> stages) {
        return stages.stream().map(PipelineVisibilityParity::stage).toList();
    }

    private static List<Object> pipelines(List<Pipeline> pipelines, boolean unordered) {
        return (unordered ? pipelines.stream().sorted(Comparator.comparingInt(Pipeline::getId)) : pipelines.stream())
            .map(PipelineVisibilityParity::pipeline).toList();
    }

    private static Object stage(Stage stage) {
        return stage == null ? null : Arrays.asList(stage.getId(), stage.getWorkspaceId(), stage.getName(),
            stage.getPipeline() == null ? null : stage.getPipeline().getId(), stage.getPosition(),
            stage.isSuccess(), stage.isFailure());
    }

    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.getFirst();
    }
}

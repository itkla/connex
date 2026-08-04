package ooo.klae.connex.backend.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Bounded, content-free diagnostic that can focus a workflow node, edge, or inspector field. */
@JsonInclude(Include.ALWAYS)
public record WorkflowDiagnosticDto(
    WorkflowDiagnosticCode code,
    String nodeId,
    String edgeId,
    String fieldPath,
    Map<String, String> params
) {

    private static final int MAX_PARAMS = 8;
    private static final int MAX_PARAM_VALUE_LENGTH = 64;
    private static final Pattern PARAM_KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,31}");

    public WorkflowDiagnosticDto {
        if (code == null) {
            throw new IllegalArgumentException("Workflow diagnostic code is required");
        }
        Map<String, String> bounded = new LinkedHashMap<>();
        if (params != null) {
            if (params.size() > MAX_PARAMS) {
                throw new IllegalArgumentException("Workflow diagnostic has too many parameters");
            }
            params.forEach((key, value) -> {
                if (key == null || !PARAM_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("Workflow diagnostic parameter key is invalid");
                }
                if (value == null || value.length() > MAX_PARAM_VALUE_LENGTH) {
                    throw new IllegalArgumentException("Workflow diagnostic parameter value is invalid");
                }
                bounded.put(key, value);
            });
        }
        params = Map.copyOf(bounded);
    }

    /** Returns the same diagnostic focused on a concrete workflow node and inspector path. */
    public WorkflowDiagnosticDto atNode(String focusedNodeId, String pathPrefix) {
        String focusedPath = fieldPath;
        if (pathPrefix != null && !pathPrefix.isBlank()) {
            focusedPath = fieldPath == null || fieldPath.isBlank()
                ? pathPrefix
                : pathPrefix + "." + fieldPath;
        }
        return new WorkflowDiagnosticDto(
            code,
            nodeId == null ? focusedNodeId : nodeId,
            edgeId,
            focusedPath,
            params);
    }
}

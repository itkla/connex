package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The workspace selected by a membership mutation, or {@code null} when no active membership remains.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkspaceSelectionDto(Integer activeWorkspaceId) {}

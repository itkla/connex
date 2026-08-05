package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Natural-language request for an unsaved report definition preview.
 * @param prompt requested report outcome
 */
public record ReportComposerRequest(
        @NotBlank @Size(max = 1200) String prompt) {
}

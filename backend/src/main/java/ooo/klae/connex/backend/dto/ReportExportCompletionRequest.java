package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Client-prepared image release request; counts and report identity are server-owned. */
public record ReportExportCompletionRequest(@NotNull @Pattern(regexp = "png") String format) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown report export completion field");
    }
}

package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Client input to generate a document on a deal from a template. */
@Data
@NoArgsConstructor
public class GenerateDocumentRequest {
    @NotNull
    @Positive
    private Integer templateId;
}

package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bounded company-list request that carries a smart-segment definition in the body rather than
 * serializing every evaluated company id into a query string.
 */
@Data
@NoArgsConstructor
public class CompanySegmentQueryRequest {

    private int page = 1;

    private int size = 25;

    @Size(max = 255)
    private String q;

    @Size(max = 32)
    private String sort;

    @Size(max = 8)
    private String dir;

    @Size(max = 32)
    private List<@NotBlank @Size(max = 255) String> industry;

    private boolean noIndustry;

    @NotNull
    @Valid
    private SegmentDefinition definition;
}

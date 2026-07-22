package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bounded deal-list request carrying a Smart Segment definition and the complete native filter
 * contract in the body.
 */
@Data
@NoArgsConstructor
public class DealSegmentQueryRequest {

    private int page = 1;

    private int size = 25;

    @Size(max = 255)
    private String q;

    @Size(max = 32)
    private String sort;

    @Size(max = 8)
    private String dir;

    @Size(max = 16)
    private String currency;

    @Size(max = 100)
    private List<@Positive Integer> pipelineId;

    @Size(max = 100)
    private List<@Positive Integer> stageId;

    @Size(max = 100)
    private List<@Positive Integer> companyId;

    private boolean noCompany;

    @Size(max = 100)
    private List<@NotBlank @Size(max = 16) String> status;

    @Size(max = 100)
    private List<@NotBlank @Size(max = 16) String> risk;

    @Size(max = 16)
    private String scope;

    @Size(max = 100)
    private List<@Positive Integer> memberIds;

    @NotNull
    @Valid
    private SegmentDefinition definition;
}

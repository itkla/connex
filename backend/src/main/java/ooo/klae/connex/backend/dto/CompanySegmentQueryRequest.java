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
 * Bounded company-list request that carries a smart-segment definition in the body rather than
 * serializing every evaluated company id into a query string, together with the complete native
 * company filter contract: search, industry, owner scope, and relationship warmth. Every filter the
 * browser can show alongside a segment is a field here, so none of them is silently discarded as an
 * unknown property.
 *
 * <p>The warmth horizon's 1–3650 day range and the band vocabulary are enforced by
 * {@link WarmthFilter#fromRequest}, the same resolver {@code GET /api/companies/page} uses; the
 * annotations here only bound the body's size.
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

    @Size(max = 16)
    private String scope;

    @Size(max = 100)
    private List<@Positive Integer> memberIds;

    @Size(max = 5)
    private List<@NotBlank @Size(max = 16) String> warmthBands;

    private boolean noWarmth;

    private Integer goesColdWithinDays;

    @NotNull
    @Valid
    private SegmentDefinition definition;
}

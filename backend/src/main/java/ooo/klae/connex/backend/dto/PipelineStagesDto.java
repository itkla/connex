package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The complete stage set a pipeline should end up with, in the order it should be shown. Entries
 * carrying an {@code id} are existing stages to keep; entries without one are created. Any stage of
 * the pipeline absent from the list is removed.
 */
@Data
@NoArgsConstructor
public class PipelineStagesDto {
    @NotNull
    private List<@Valid StageDto> stages = new ArrayList<>();
}

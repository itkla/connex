package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Stage;

/**
 * The complete stage set a pipeline should end up with, in the order it should be shown. Entries
 * carrying an {@code id} are existing stages to keep; entries without one are created. Any stage of
 * the pipeline absent from the list is removed.
 */
@Data
@NoArgsConstructor
public class PipelineStagesDto {
    @NotNull
    private List<@Valid Entry> stages = new ArrayList<>();

    /**
     * One stage in the intended set. There is no position: order in {@link #stages} is the order, and
     * the server renumbers positions to match. Declared with only a no-args constructor so an entry
     * that omits {@code success} or {@code failure} binds to the primitive default rather than being
     * rejected as a malformed body.
     */
    @Data
    @NoArgsConstructor
    public static class Entry {
        private Integer id;

        @NotBlank
        @Size(max = 128)
        private String name;

        private boolean success;
        private boolean failure;

        public Stage toBean() {
            Stage stage = new Stage();
            if (id != null) stage.setId(id);
            stage.setName(name);
            stage.setSuccess(success);
            stage.setFailure(failure);
            return stage;
        }
    }
}

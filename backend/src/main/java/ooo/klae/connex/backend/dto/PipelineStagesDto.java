package ooo.klae.connex.backend.dto;

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
 * carrying an {@code id} are existing stages to keep; entries without one are created.
 *
 * <p>Both fields are required and are left null until deserialization, so a body that omits either
 * fails validation rather than being read as "remove every stage".
 */
@Data
@NoArgsConstructor
public class PipelineStagesDto {
    /**
     * The stage ids the editor had loaded when it built {@link #stages}. Only these may be removed,
     * so a stage another editor added after the load survives instead of being silently deleted.
     */
    @NotNull
    private List<@NotNull Integer> knownStageIds;

    @NotNull
    private List<@NotNull @Valid Entry> stages;

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

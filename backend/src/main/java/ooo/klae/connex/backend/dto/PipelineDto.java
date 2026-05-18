package ooo.klae.connex.backend.dto;

import java.util.Arrays;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineDto {

    private int id;

    @NotBlank
    @Size(max = 128)
    private String name;

    private int[] stageIds;

    private String createdAt;
    private String updatedAt;

    public static PipelineDto from(Pipeline p) {
        if (p == null) return null;
        PipelineDto dto = new PipelineDto();
        dto.id = p.getId();
        dto.name = p.getName();
        dto.stageIds = p.getStages() == null ? null : Arrays.stream(p.getStages()).mapToInt(Stage::getId).toArray();
        dto.createdAt = p.getCreatedAt();
        dto.updatedAt = p.getUpdatedAt();
        return dto;
    }

    public Pipeline toBean() {
        Pipeline p = new Pipeline();
        p.setId(id);
        p.setName(name);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(updatedAt);
        return p;
    }
}

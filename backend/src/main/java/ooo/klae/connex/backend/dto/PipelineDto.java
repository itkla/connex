package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Pipeline;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineDto {

    private int id;

    @NotBlank
    @Size(max = 128)
    private String name;

    private String createdAt;
    private String updatedAt;

    public static PipelineDto from(Pipeline p) {
        if (p == null) return null;
        PipelineDto dto = new PipelineDto();
        dto.id = p.getId();
        dto.name = p.getName();
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

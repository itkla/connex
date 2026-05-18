package ooo.klae.connex.backend.dto;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StageDto {

    private int id;

    @NotBlank
    @Size(max = 128)
    private String name;

    @JsonIdentityReference(alwaysAsId = true)
    private Pipeline pipeline;

    @PositiveOrZero
    private int position;

    private int[] dealIds;

    public static StageDto from(Stage s) {
        if (s == null) return null;
        StageDto dto = new StageDto();
        dto.id = s.getId();
        dto.name = s.getName();
        dto.pipeline = s.getPipeline();
        dto.position = s.getPosition();
        dto.dealIds = s.getDeals() == null ? null : Arrays.stream(s.getDeals()).mapToInt(Deal::getId).toArray(); // is getDeals null? yes : no, then map to array of deal ids
        return dto;
    }

    public Stage toBean() {
        Stage s = new Stage();
        s.setId(id);
        s.setName(name);
        s.setPipeline(pipeline);
        s.setPosition(position);
        return s;
    }
}

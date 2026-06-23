package ooo.klae.connex.backend.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DealCollaboratorsDto {
    @NotNull
    private List<@Positive Integer> userIds = new ArrayList<>();
}
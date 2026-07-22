package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.StageDto;
import ooo.klae.connex.backend.services.PipelineService;

@ExtendWith(MockitoExtension.class)
class PipelineControllerTest {
    @Mock private PipelineService pipelineService;

    @Test
    void getAllStagesReturnsCompactBatchDtos() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(7);
        Stage stage = new Stage();
        stage.setId(11);
        stage.setName("Qualified");
        stage.setPipeline(pipeline);
        stage.setPosition(2);
        when(pipelineService.getAllStages()).thenReturn(List.of(stage));

        List<StageDto> response = new PipelineController(pipelineService).getAllStages();

        assertEquals(1, response.size());
        assertEquals(11, response.getFirst().getId());
        assertEquals(7, response.getFirst().getPipeline().getId());
        assertEquals(2, response.getFirst().getPosition());
        assertNull(response.getFirst().getDealIds());
    }
}

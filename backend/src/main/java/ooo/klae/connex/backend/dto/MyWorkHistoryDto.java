package ooo.klae.connex.backend.dto;

import java.util.List;

/** The latest 25 records per personal history source, with existing reference redaction. */
public record MyWorkHistoryDto(List<TaskDto> tasks, List<ActivityDto> activities, List<NoteDto> notes) {}

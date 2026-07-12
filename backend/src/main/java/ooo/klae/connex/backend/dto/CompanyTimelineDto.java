package ooo.klae.connex.backend.dto;

import java.util.List;

/** Bounded recent activity, task, and visible-note slices for one company timeline. */
public record CompanyTimelineDto(
    List<ActivityDto> activities,
    List<TaskDto> tasks,
    List<NoteDto> notes
) {}

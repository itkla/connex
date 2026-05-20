package ooo.klae.connex.backend.dto;

import java.util.Arrays;
import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Person;

/**
 * Extended view of a {@code Person} that includes fully hydrated related entities
 * (tags, deals, notes, tasks, activities).
 *
 * The lean {@link PersonDto} (id arrays only) is still used for list endpoints
 * to keep their payloads small.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class PersonDetailDto extends PersonDto {

    private List<TagDto> tags;
    private List<DealDto> deals;
    private List<NoteDto> notes;
    private List<TaskDto> tasks;
    private List<ActivityDto> activities;

    public static PersonDetailDto from(Person p) {
        if (p == null) return null;
        PersonDetailDto dto = populate(new PersonDetailDto(), p);
        dto.tags = p.getTags() == null ? null
            : Arrays.stream(p.getTags()).map(TagDto::from).toList();
        dto.deals = p.getDeals() == null ? null
            : Arrays.stream(p.getDeals()).map(DealDto::from).toList();
        dto.notes = p.getNotes() == null ? null
            : Arrays.stream(p.getNotes()).map(NoteDto::from).toList();
        dto.tasks = p.getTasks() == null ? null
            : Arrays.stream(p.getTasks()).map(TaskDto::from).toList();
        dto.activities = p.getActivities() == null ? null
            : Arrays.stream(p.getActivities()).map(ActivityDto::from).toList();
        return dto;
    }
}

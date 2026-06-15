package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultsDto {
    private List<CompanyDto> companies;
    private List<PersonDto> people;
    private List<DealDto> deals;
    private List<PipelineDto> pipelines;
    private List<TagDto> tags;
    private List<ActivityDto> activities;
    private List<NoteDto> notes;
    private List<TaskDto> tasks;
    private List<UserDto> users;
    private List<AttachmentDto> attachments;
}
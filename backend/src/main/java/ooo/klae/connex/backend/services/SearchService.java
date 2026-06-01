package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PipelineDto;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;

import org.springframework.stereotype.Service;

import java.util.List;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;

    public SearchResultsDto search(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResultsDto(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }

        // search may contain special characters, so we need to escape them
        String pattern = "%" + escapeLike(query.trim()) + "%";

        return new SearchResultsDto(
            companyMapper.search(pattern).stream().map(CompanyDto::from).toList(),
            personMapper.search(pattern).stream().map(PersonDto::from).toList(),
            dealMapper.search(pattern).stream().map(DealDto::from).toList(),
            pipelineMapper.search(pattern).stream().map(PipelineDto::from).toList(),
            tagMapper.search(pattern).stream().map(TagDto::from).toList(),
            activityMapper.search(pattern).stream().map(ActivityDto::from).toList(),
            noteMapper.search(pattern).stream().map(NoteDto::from).toList(),
            taskMapper.search(pattern).stream().map(TaskDto::from).toList(),
            userMapper.search(pattern).stream().map(UserDto::from).toList()
        );
    }

    private String escapeLike(String input) {
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
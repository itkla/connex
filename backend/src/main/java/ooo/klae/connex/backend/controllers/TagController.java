package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.services.TagService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Tag} CRUD operations.
 * Tag attach/detach endpoints live on the entity controllers
 * (e.g. {@code POST /api/persons/{id}/tags/{tagId}}).
 */

@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    /**
     * Retrieves all tags, ordered by {@code name}.
     */
    @GetMapping
    public List<TagDto> getAllTags() {
        return tagService.getAllTags().stream().map(TagDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single tag by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public TagDto getTagById(@PathVariable int id) {
        return TagDto.from(tagService.getTagById(id));
    }

    /**
     * POST endpoint to create a new tag.
     * @param dto
     * @return
     */
    @PostMapping
    public TagDto createTag(@Valid @RequestBody TagDto dto) {
        return TagDto.from(tagService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing tag.
     * @param id
     * @param dto
     * @return
     */
    @PutMapping("/{id}")
    public TagDto updateTag(@PathVariable int id, @Valid @RequestBody TagDto dto) {
        return TagDto.from(tagService.update(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete a tag by ID.
     * Cascades to all junction rows ({@code person_tag}, {@code company_tag}, {@code deal_tag}).
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteTag(@PathVariable int id) {
        tagService.delete(id);
    }

    /**
     * GET endpoint to retrieve deals tagged with this tag.
     * @param id
     * @return
     * The inverse side of {@code GET /api/deals?tagId=X}.
     */
    @GetMapping("/{id}/deals")
    public List<DealDto> getDealsForTag(@PathVariable int id) {
        return tagService.getDealsByTagId(id).stream().map(DealDto::from).toList();
    }

    /**
     * GET endpoint to retrieve people tagged with this tag.
     * @param id
     * @return
     * The inverse side of {@code GET /api/persons?tagId=X}.
     */
    @GetMapping("/{id}/people")
    public List<PersonDto> getPeopleForTag(@PathVariable int id) {
        return tagService.getPersonsByTagId(id).stream().map(PersonDto::from).toList();
    }

    /**
     * GET endpoint to retrieve companies associated with a tag.
     * @param id
     * @return
     */
    @GetMapping("/{id}/companies")
    public List<CompanyDto> getCompaniesForTag(@PathVariable int id) {
        return tagService.getCompaniesByTagId(id).stream().map(CompanyDto::from).toList();
    }
}

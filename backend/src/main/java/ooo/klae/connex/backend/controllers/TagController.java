package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.services.TagService;

import java.util.List;

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
     * GET endpoint to retrieve all tags.
     * @return
     */
    @GetMapping
    public List<Tag> getAllTags() {
        return tagService.getAllTags();
    }

    /**
     * GET endpoint to retrieve a single tag by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Tag getTagById(@PathVariable int id) {
        return tagService.getTagById(id);
    }

    //TODO: add filtering by companyId, userId, etc. once those concepts are implemented

    /**
     * 
     * @param tag
     * @return
     */
    @PostMapping
    public Tag createTag(@RequestBody Tag tag) {
        return tagService.create(tag);
    }

    /**
     * PUT endpoint to update an existing tag.
     * @param id
     * @param tag
     * @return
     */
    @PutMapping("/{id}")
    public Tag updateTag(@PathVariable int id, @RequestBody Tag tag) {
        return tagService.update(id, tag);
    }

    /**
     * DELETE endpoint to delete a tag by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteTag(@PathVariable int id) {
        tagService.delete(id);
    }
}

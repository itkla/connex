package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.dto.SavedViewDto;
import ooo.klae.connex.backend.services.SavedViewService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for per-user saved views. No {@code @RequirePermission}: a member manages
 * only their own views, enforced by workspace + user scoping in the service and mapper.
 */
@RestController
@RequestMapping("/api/saved-views")
@RequiredArgsConstructor
public class SavedViewController {
    private final SavedViewService viewService;

    /**
     * GET the current user's saved views, optionally for one record type.
     */
    @GetMapping
    public List<SavedViewDto> list(@RequestParam(required = false) String recordType) {
        return viewService.list(recordType).stream().map(this::toDto).toList();
    }

    /**
     * GET a single saved view owned by the current user.
     */
    @GetMapping("/{id}")
    public SavedViewDto getOne(@PathVariable int id) {
        return toDto(viewService.getById(id));
    }

    /**
     * POST creates a saved view for the current user.
     */
    @PostMapping
    public SavedViewDto create(@Valid @RequestBody SavedViewDto dto) {
        return toDto(viewService.create(dto.getRecordType(), dto.getName(), dto.getConfig()));
    }

    /**
     * PUT updates a saved view's name, config, and/or position.
     */
    @PutMapping("/{id}")
    public SavedViewDto update(@PathVariable int id, @Valid @RequestBody SavedViewDto dto) {
        return toDto(viewService.update(id, dto.getName(), dto.getConfig(), dto.getPosition()));
    }

    /**
     * DELETE removes a saved view.
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        viewService.delete(id);
    }

    private SavedViewDto toDto(SavedView view) {
        SavedViewDto dto = SavedViewDto.from(view);
        dto.setConfig(viewService.parseConfig(view.getConfigJson()));
        return dto;
    }
}

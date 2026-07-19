package ooo.klae.connex.backend.controllers;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SavedViewCreateRequest;
import ooo.klae.connex.backend.dto.SavedViewDefaultDto;
import ooo.klae.connex.backend.dto.SavedViewDefaultRequest;
import ooo.klae.connex.backend.dto.SavedViewDto;
import ooo.klae.connex.backend.dto.SavedViewPinRequest;
import ooo.klae.connex.backend.dto.SavedViewUpdateRequest;
import ooo.klae.connex.backend.services.SavedViewService;

/** Authenticated active-workspace endpoints for saved views, sharing, pins, and defaults. */
@RestController
@RequestMapping("/api/saved-views")
@RequiredArgsConstructor
public class SavedViewController {
    private final SavedViewService viewService;

    /** Returns accessible saved views for one required record type. */
    @GetMapping
    public List<SavedViewDto> list(@RequestParam String recordType) {
        return viewService.list(recordType).stream().map(SavedViewDto::from).toList();
    }

    /** Resolves a stable saved-view link within the active workspace. */
    @GetMapping("/{id:\\d+}")
    public SavedViewDto getOne(@PathVariable int id) {
        return SavedViewDto.from(viewService.getById(id));
    }

    /** Creates a caller-owned saved view. */
    @PostMapping
    public ResponseEntity<SavedViewDto> create(@Valid @RequestBody SavedViewCreateRequest request) {
        SavedViewDto created = SavedViewDto.from(viewService.create(request));
        return ResponseEntity.created(URI.create("/api/saved-views/" + created.getId())).body(created);
    }

    /** Replaces a caller-owned saved view. */
    @PutMapping("/{id:\\d+}")
    public SavedViewDto update(@PathVariable int id, @Valid @RequestBody SavedViewUpdateRequest request) {
        return SavedViewDto.from(viewService.update(id, request));
    }

    /** Deletes a caller-owned saved view. */
    @DeleteMapping("/{id:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        viewService.delete(id);
    }

    /** Returns the caller's accessible pins across all record types. */
    @GetMapping("/pins")
    public List<SavedViewDto> listPins() {
        return viewService.listPins().stream().map(SavedViewDto::from).toList();
    }

    /** Idempotently pins or repositions an accessible saved view. */
    @PutMapping("/{id:\\d+}/pin")
    public SavedViewDto pin(@PathVariable int id, @Valid @RequestBody SavedViewPinRequest request) {
        return SavedViewDto.from(viewService.pin(id, request.getPosition()));
    }

    /** Idempotently removes the caller's pin from an accessible saved view. */
    @DeleteMapping("/{id:\\d+}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpin(@PathVariable int id) {
        viewService.unpin(id);
    }

    /** Returns the caller's accessible default for one record type. */
    @GetMapping("/defaults/{recordType}")
    public SavedViewDefaultDto getDefault(@PathVariable String recordType) {
        var view = viewService.getDefault(recordType);
        return new SavedViewDefaultDto(view == null ? null : SavedViewDto.from(view));
    }

    /** Atomically selects an accessible same-type default. */
    @PutMapping("/defaults/{recordType}")
    public SavedViewDefaultDto setDefault(
            @PathVariable String recordType, @Valid @RequestBody SavedViewDefaultRequest request) {
        return new SavedViewDefaultDto(
            SavedViewDto.from(viewService.setDefault(recordType, request.getSavedViewId())));
    }

    /** Resets the caller's default for one record type. */
    @DeleteMapping("/defaults/{recordType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetDefault(@PathVariable String recordType) {
        viewService.resetDefault(recordType);
    }
}

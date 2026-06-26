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

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.dto.CustomFieldDefinitionDto;
import ooo.klae.connex.backend.services.CustomFieldDefinitionService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for the custom-field catalog. Every operation requires
 * {@code CUSTOM_FIELD_MANAGE} (enforced on the service) — this is the admin
 * management surface, not the member-facing record read path.
 */
@RestController
@RequestMapping("/api/custom-fields")
@RequiredArgsConstructor
public class CustomFieldController {
    private final CustomFieldDefinitionService definitionService;

    /**
     * Lists field definitions, optionally filtered to one entity type.
     */
    @GetMapping
    public List<CustomFieldDefinitionDto> list(@RequestParam(required = false) String entityType) {
        List<CustomFieldDefinition> definitions = (entityType == null || entityType.isBlank())
            ? definitionService.getAll()
            : definitionService.getByEntityType(entityType);
        return definitions.stream().map(this::toDto).toList();
    }

    /**
     * Retrieves a single field definition by ID.
     */
    @GetMapping("/{id}")
    public CustomFieldDefinitionDto getById(@PathVariable int id) {
        return toDto(definitionService.getById(id));
    }

    /**
     * Defines a new custom field.
     */
    @PostMapping
    public CustomFieldDefinitionDto create(@Valid @RequestBody CustomFieldDefinitionDto dto) {
        return toDto(definitionService.create(dto.toBean(), dto.getOptions()));
    }

    /**
     * Updates an existing field's editable attributes.
     */
    @PutMapping("/{id}")
    public CustomFieldDefinitionDto update(@PathVariable int id, @Valid @RequestBody CustomFieldDefinitionDto dto) {
        return toDto(definitionService.update(id, dto.toBean(), dto.getOptions()));
    }

    /**
     * Deletes a field and its values.
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        definitionService.delete(id);
    }

    /**
     * Maps a definition to its API form, attaching the typed options the service parses
     * from the stored {@code options_json}.
     */
    private CustomFieldDefinitionDto toDto(CustomFieldDefinition def) {
        CustomFieldDefinitionDto dto = CustomFieldDefinitionDto.from(def);
        dto.setOptions(definitionService.parseOptions(def.getOptionsJson()));
        return dto;
    }
}

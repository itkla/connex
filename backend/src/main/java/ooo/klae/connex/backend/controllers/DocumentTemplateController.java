package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.DocumentTemplateDto;
import ooo.klae.connex.backend.services.DocumentTemplateService;

/** REST controller for commercial-document templates. */
@RestController
@RequestMapping("/api/document-templates")
@RequiredArgsConstructor
public class DocumentTemplateController {
    private final DocumentTemplateService templateService;

    @GetMapping
    public List<DocumentTemplateDto> getAll() {
        return templateService.getAll().stream().map(DocumentTemplateDto::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentTemplateDto getById(@PathVariable int id) {
        return DocumentTemplateDto.from(templateService.getById(id));
    }

    @PostMapping
    public DocumentTemplateDto create(@Valid @RequestBody DocumentTemplateDto dto) {
        return DocumentTemplateDto.from(templateService.create(dto.toBean()));
    }

    @PutMapping("/{id}")
    public DocumentTemplateDto update(@PathVariable int id, @Valid @RequestBody DocumentTemplateDto dto) {
        return DocumentTemplateDto.from(templateService.update(id, dto.toBean()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        templateService.delete(id);
    }
}

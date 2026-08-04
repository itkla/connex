package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowRecipeDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallRequest;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewDto;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewRequest;
import ooo.klae.connex.backend.services.WorkflowRecipeService;

/** Curated workflow recipe catalog, preview, and installation endpoints. */
@RestController
@RequestMapping("/api/workflow-recipes")
@RequiredArgsConstructor
public class WorkflowRecipeController {

    private final WorkflowRecipeService recipeService;

    @GetMapping
    public List<WorkflowRecipeDto> list() {
        return recipeService.list();
    }

    @GetMapping("/{recipeKey}")
    public WorkflowRecipeDto get(@PathVariable String recipeKey) {
        return recipeService.get(recipeKey);
    }

    @PostMapping("/{recipeKey}/preview")
    public WorkflowRecipePreviewDto preview(
            @PathVariable String recipeKey,
            @Valid @RequestBody WorkflowRecipePreviewRequest request) {
        return recipeService.preview(recipeKey, request);
    }

    @PostMapping("/{recipeKey}/install")
    public WorkflowRecipeInstallDto install(
            @PathVariable String recipeKey,
            @Valid @RequestBody WorkflowRecipeInstallRequest request) {
        return recipeService.install(recipeKey, request);
    }
}

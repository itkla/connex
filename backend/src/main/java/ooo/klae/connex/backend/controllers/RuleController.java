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

import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.services.RuleService;

/**
 * CRUD for automation rules. Every operation is gated by {@code RULE_MANAGE} in the service, and
 * {@code system}-mode rules additionally require the admin tier. All access is workspace-scoped.
 */
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @GetMapping
    public List<RuleDto> list() {
        return ruleService.list();
    }

    @GetMapping("/{id}")
    public RuleDto get(@PathVariable int id) {
        return ruleService.getById(id);
    }

    @GetMapping("/{id}/executions")
    public List<RuleExecution> executions(@PathVariable int id) {
        return ruleService.executions(id);
    }

    @PostMapping
    public RuleDto create(@Valid @RequestBody RuleRequest request) {
        return ruleService.create(request);
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable int id, @Valid @RequestBody RuleRequest request) {
        return ruleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        ruleService.delete(id);
    }
}

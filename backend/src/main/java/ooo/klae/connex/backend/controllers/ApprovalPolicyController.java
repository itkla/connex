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

import ooo.klae.connex.backend.dto.ApprovalPolicyDto;
import ooo.klae.connex.backend.dto.ApprovalPolicyImpactDto;
import ooo.klae.connex.backend.services.ApprovalPolicyService;

/** REST controller for document approval policies. */
@RestController
@RequestMapping("/api/approval-policies")
@RequiredArgsConstructor
public class ApprovalPolicyController {
    private final ApprovalPolicyService policyService;

    @GetMapping
    public List<ApprovalPolicyDto> getAll() {
        return policyService.getAll().stream().map(ApprovalPolicyDto::from).toList();
    }

    @PostMapping
    public ApprovalPolicyDto create(@Valid @RequestBody ApprovalPolicyDto dto) {
        return ApprovalPolicyDto.from(policyService.create(dto.toBean()));
    }

    @PutMapping("/{id}")
    public ApprovalPolicyDto update(@PathVariable int id, @Valid @RequestBody ApprovalPolicyDto dto) {
        return ApprovalPolicyDto.from(
            policyService.update(id, dto.toBean(), dto.isConfirmInvalidation()));
    }

    @PostMapping("/{id}/impact")
    public ApprovalPolicyImpactDto impact(@PathVariable int id,
            @Valid @RequestBody ApprovalPolicyDto dto) {
        return policyService.impact(id, dto.toBean());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        policyService.delete(id);
    }
}

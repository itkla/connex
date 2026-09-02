package ooo.klae.connex.backend.controllers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@RestController
@RequiredArgsConstructor
@TenantJournalAttributable
@ConditionalOnProperty(
    prefix = "connex.record-creation",
    name = "guided-cutover-enabled",
    havingValue = "false",
    matchIfMissing = true)
public class LegacyRecordCreationController {
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;

    @PostMapping("/api/persons")
    public PersonDto createPerson(@Valid @RequestBody PersonDto request) {
        return PersonDto.from(personService.createReviewed(
            request.toBean(), request.getDuplicateReviewToken()));
    }

    @PostMapping("/api/companies")
    public CompanyDto createCompany(@Valid @RequestBody CompanyDto request) {
        return CompanyDto.from(companyService.createCompanyReviewed(
            request.toBean(), request.getDuplicateReviewToken()));
    }

    @PostMapping("/api/deals")
    public DealDto createDeal(@Valid @RequestBody DealDto request) {
        return DealDto.from(dealService.createReviewed(
            request.toBean(), request.getDuplicateReviewToken()));
    }
}

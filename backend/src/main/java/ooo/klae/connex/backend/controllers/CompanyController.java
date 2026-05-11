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

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.services.CompanyService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Company} CRUD operations.
 * Accepts and returns {@code CompanyDto}. Delegates to {@code CompanyService}.
 */

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companyService;

    /**
     * Retrieves all companies, optionally filtered by tag.
     * @return
     */
    @GetMapping
    public List<CompanyDto> getAllCompanies(@RequestParam(required = false) Integer tagId) {
        List<Company> companies = (tagId != null) ? companyService.getCompaniesByTagId(tagId) : companyService.getAllCompanies();
        return companies.stream().map(CompanyDto::from).toList();
    }

    /**
     * GET Retrieves a company by ID. Throws RuntimeException if not found.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public CompanyDto oneCompany(@PathVariable int id) {
        return CompanyDto.from(companyService.getCompanyById(id));
    }

    /**
     * POST Creates a new company.
     * @param company
     * @return
     */
    @PostMapping
    public CompanyDto createCompany(@Valid @RequestBody CompanyDto dto) {
        return CompanyDto.from(companyService.createCompany(dto.toBean()));
    }

    /**
     * PUT Updates an existing company.
     * @param id
     * @param company
     * @return
     */
    @PutMapping("/{id}")
    public CompanyDto updateCompany(@PathVariable int id, @Valid @RequestBody CompanyDto dto) {
        return CompanyDto.from(companyService.updateCompany(id, dto.toBean()));
    }

    /**
     * DELETE Deletes a company by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteCompany(@PathVariable int id) {
        companyService.deleteCompany(id);
    }

    /**
     * GET Retrieves tags associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForCompany(@PathVariable int id) {
        return companyService.getTagsByCompanyId(id).stream().map(TagDto::from).toList();
    }

    /**
     * POST Adds a tag to a company.
     * @param id
     * @param tagId
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToCompany(@PathVariable int id, @PathVariable int tagId) {
        companyService.addTag(id, tagId);
    }

    /**
     * DELETE Removes a tag from a company.
     * @param id
     * @param tagId
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromCompany(@PathVariable int id, @PathVariable int tagId) {
        companyService.removeTag(id, tagId);
    }

    /**
     * PUT endpoint to replace the tags associated with a company.
     * @param id
     * @param tagIds
     * @return List of tags
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForCompany(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return companyService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }

    /**
     * GET endpoint to retrieve people associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/people")
    public List<PersonDto> getPeopleForCompany(@PathVariable int id) {
        return companyService.getPersonsByCompanyId(id).stream().map(PersonDto::from).toList();
    }

    /**
     * GET endpoint to retrieve deals associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/deals")
    public List<DealDto> getDealsForCompany(@PathVariable int id) {
        return companyService.getDealsByCompanyId(id).stream().map(DealDto::from).toList();
    }
}

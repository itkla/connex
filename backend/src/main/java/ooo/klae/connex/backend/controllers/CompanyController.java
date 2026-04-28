package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Tag;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;




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
    public List<Company> getAllCompanies(@RequestParam(required = false) Integer tagId) {
        if (tagId != null) return companyService.getCompaniesByTagId(tagId);
        return companyService.getAllCompanies();
    }

    /**
     * GET Retrieves a company by ID. Throws RuntimeException if not found.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Company oneCompany(@PathVariable int id) {
        return companyService.getCompanyById(id);
    }

    /**
     * POST Creates a new company.
     * @param company
     * @return
     */
    @PostMapping
    public Company createCompany(@RequestBody Company company) {
        return companyService.createCompany(company);
    }

    /**
     * PUT Updates an existing company.
     * @param id
     * @param company
     * @return
     */
    @PutMapping("/{id}")
    public Company updateCompany(@PathVariable int id, @RequestBody Company company) {
        company.setId(id);
        return companyService.updateCompany(id, company);
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
    public List<Tag> getTagsForCompany(@PathVariable int id) {
        return companyService.getTagsByCompanyId(id);
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
}

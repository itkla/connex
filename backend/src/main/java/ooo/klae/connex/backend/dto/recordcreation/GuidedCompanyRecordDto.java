package ooo.klae.connex.backend.dto.recordcreation;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.URL;

import ooo.klae.connex.backend.beans.Company;

public record GuidedCompanyRecordDto(
    @NotBlank @Size(max = 255) String name,
    @URL(regexp = "^https?://[\\w-]+(\\.[\\w-]+)+.*$",
        message = "Please enter a valid URL including http:// or https://")
    @Size(max = 255) String website,
    @Size(max = 128) String industry,
    @Size(max = 64) String phone,
    @Size(max = 512) String address,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Pattern(regexp = "^[0-9a-f]{64}$") String duplicateReviewToken
) {
    public Company toBean() {
        Company company = new Company();
        company.setName(name);
        company.setWebsite(website);
        company.setIndustry(industry);
        company.setPhone(phone);
        company.setAddress(address);
        return company;
    }
}

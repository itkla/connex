package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an organization (company or account) in the CRM.
 * A Company can have many associated {@link Person}s and {@link Deal}s.
 * Mapped via {@code CompanyMapper} / {@code CompanyMapper.xml}.
 */

@Data
@NoArgsConstructor
public class Company {
    private int id; // auto-incremented primary key
    private String name;
    private String website;
    private String industry;
    private String phone;
    private String address;
    private Person[] people; // associated contacts
    private Deal[] deals; // associated deals
    private String[] tags; // e.g. "VIP", "Prospect", "Customer"
    private String createdAt;
    private String updatedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Person[] getPeople() {
        return people;
    }

    public void setPeople(Person[] people) {
        this.people = people;
    }

    public Deal[] getDeals() {
        return deals;
    }

    public void setDeals(Deal[] deals) {
        this.deals = deals;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}

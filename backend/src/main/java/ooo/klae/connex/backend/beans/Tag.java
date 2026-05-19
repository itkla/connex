package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a label used to categorize {@link Person}s, {@link Company}s, or {@link Deal}s.
 * Tags are many-to-many with the records they label; no dedicated mapper — managed inline by each entity's mapper.
 */

@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id", scope = Tag.class)
@Data
@NoArgsConstructor
public class Tag {
    private int id;
    private String name;
    private String color; // hex color code, e.g. "#FF5733"
    private Person[] people;   // contacts labelled with this tag
    private Company[] companies; // companies labelled with this tag
    private Deal[] deals;      // deals labelled with this tag

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

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public Person[] getPeople() {
        return people;
    }

    public void setPeople(Person[] people) {
        this.people = people;
    }

    public Company[] getCompanies() {
        return companies;
    }

    public void setCompanies(Company[] companies) {
        this.companies = companies;
    }

    public Deal[] getDeals() {
        return deals;
    }

    public void setDeals(Deal[] deals) {
        this.deals = deals;
    }
}

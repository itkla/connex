package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {

    private Integer id;

    private Integer workspaceId;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 64)
    private String phone;

    // @JsonIdentityReference(alwaysAsId = true) // this was casting Company to an int, causing it to crash
    private Company company;

    private Integer companyId;

    @Size(max = 128)
    private String title;

    private int[] tagIds;
    private int[] dealIds;
    private int[] taskIds;
    private int[] activityIds;
    private int[] noteIds;

    private String createdAt;
    private String updatedAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String imageUrl;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean riskExcluded;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean introExcluded;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime suspendedAt;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime provisionCeasedAt;

    public static PersonDto from(Person p) {
        if (p == null) return null;
        return populate(new PersonDto(), p);
    }

    // if !dto, then create a new PersonDto with the values from the Person object
    protected static <T extends PersonDto> T populate(T dto, Person p) {
        dto.setId(p.getId());
        dto.setWorkspaceId(p.getWorkspaceId());
        dto.setName(p.getName());
        dto.setEmail(p.getEmail());
        dto.setPhone(p.getPhone());
        dto.setCompany(p.getCompany());
        dto.setCompanyId(p.getCompany() == null ? null : p.getCompany().getId());
        dto.setTitle(p.getTitle());
        dto.setTagIds(p.getTags() == null ? null : Arrays.stream(p.getTags()).mapToInt(Tag::getId).toArray());
        dto.setDealIds(p.getDeals() == null ? null : Arrays.stream(p.getDeals()).mapToInt(Deal::getId).toArray());
        dto.setTaskIds(p.getTasks() == null ? null : Arrays.stream(p.getTasks()).mapToInt(Task::getId).toArray());
        dto.setActivityIds(p.getActivities() == null ? null : Arrays.stream(p.getActivities()).mapToInt(Activity::getId).toArray());
        dto.setNoteIds(p.getNotes() == null ? null : Arrays.stream(p.getNotes()).mapToInt(Note::getId).toArray());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setUpdatedAt(p.getUpdatedAt());
        dto.setImageUrl(p.getImageUrl());
        dto.setRiskExcluded(p.isRiskExcluded());
        dto.setIntroExcluded(p.isIntroExcluded());
        dto.setSuspendedAt(p.getSuspendedAt());
        dto.setProvisionCeasedAt(p.getProvisionCeasedAt());
        return dto;
    }

    public Person toBean() {
        Person p = new Person();
        if (id != null) p.setId(id);
        p.setName(name);
        p.setEmail(email);
        p.setPhone(phone);
        // if company is not null set it to company id stub, otherwise set it to company
        if (companyId != null) {
            Company stub = new Company();
            stub.setId(companyId);
            p.setCompany(stub);
        } else {
            p.setCompany(company);
        }
        p.setTitle(title);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(updatedAt);
        p.setImageUrl(imageUrl);
        return p;
    }
}

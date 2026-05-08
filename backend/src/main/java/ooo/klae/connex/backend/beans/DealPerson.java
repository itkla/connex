package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIdentityReference;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealPerson {
    @JsonIdentityReference(alwaysAsId = true)
    private Person person;
    private String role;
}

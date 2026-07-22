package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One of a contact's connections, from that contact's point of view: the edge plus the display
 * details of the person on the other end. Populated directly by a MyBatis projection.
 */
@Data
@NoArgsConstructor
public class PersonConnectionDto {
    private int id;
    private int personId;
    private String personName;
    private Integer companyId;
    private String companyName;
    private String type;
    private int strength;
    private String note;
    private LocalDateTime suspendedAt;
    private LocalDateTime provisionCeasedAt;
}

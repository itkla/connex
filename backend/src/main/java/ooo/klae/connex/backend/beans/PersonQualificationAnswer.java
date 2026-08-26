package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One contact's answer to one qualification criterion (#559).
 *
 * <p>Like the lifecycle stage it feeds, this is the owning workspace's own assessment of its own
 * pipeline: a workspace that merely has the contact shared in can neither read nor write it.
 *
 * <p>Mapped via {@code PersonQualificationMapper} / {@code PersonQualificationMapper.xml}.
 */
@Data
@NoArgsConstructor
public class PersonQualificationAnswer {
    private int workspaceId;
    private int personId;
    private int criterionId;
    private QualificationAnswer answer;
    private Integer answeredById;
    private LocalDateTime answeredAt;
}

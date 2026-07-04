package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.UserDashboard;

/**
 * API representation of a {@code UserDashboard}. {@code layout} is an opaque JSON object owned
 * by the client (widget order, spans, visibility); it is stored and returned verbatim. A GET for
 * a user who has never customized their dashboard returns {@code layout = null} so the client
 * falls back to its default layout.
 */
@Data
@NoArgsConstructor
public class DashboardLayoutDto {
    @NotNull
    private Object layout;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String updatedAt;

    /**
     * Maps a bean to a DTO, leaving {@code layout} unset (the controller resolves it from the
     * stored JSON).
     */
    public static DashboardLayoutDto from(UserDashboard dashboard) {
        DashboardLayoutDto dto = new DashboardLayoutDto();
        dto.setUpdatedAt(dashboard.getUpdatedAt());
        return dto;
    }

    /**
     * The empty representation returned when the current user has no saved layout.
     */
    public static DashboardLayoutDto empty() {
        return new DashboardLayoutDto();
    }
}

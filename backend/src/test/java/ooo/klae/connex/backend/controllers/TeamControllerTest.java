package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.TeamDto;
import ooo.klae.connex.backend.dto.TeamMemberRequest;
import ooo.klae.connex.backend.dto.TeamRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.TeamService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class TeamControllerTest {
    @Mock private TeamService service;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TeamController(service))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
    }

    @Test
    void readsRequireMembershipWithoutASeparatePermissionAnnotation() throws Exception {
        assertNull(TeamController.class.getMethod("list", boolean.class)
            .getAnnotation(RequirePermission.class));
        assertNull(TeamController.class.getMethod("get", int.class)
            .getAnnotation(RequirePermission.class));
    }

    @Test
    void everyMutationRequiresTeamManage() throws Exception {
        List<Method> methods = List.of(
            TeamController.class.getMethod("create", TeamRequest.class),
            TeamController.class.getMethod("update", int.class, TeamRequest.class),
            TeamController.class.getMethod("archive", int.class),
            TeamController.class.getMethod("addMember", int.class, TeamMemberRequest.class),
            TeamController.class.getMethod("removeMember", int.class, int.class));

        for (Method method : methods) {
            assertEquals(Permission.TEAM_MANAGE,
                method.getAnnotation(RequirePermission.class).value(), method.getName());
        }
    }

    @Test
    void endpointShapesAndValidationMatchTheContract() throws Exception {
        TeamDto team = new TeamDto(31, "Sales", null, null, List.of(), null);
        when(service.list(false)).thenReturn(List.of(team));
        when(service.get(31)).thenReturn(team);
        when(service.create(any(TeamRequest.class))).thenReturn(team);
        when(service.update(org.mockito.ArgumentMatchers.eq(31), any(TeamRequest.class))).thenReturn(team);
        when(service.archive(31)).thenReturn(team);
        when(service.addMember(org.mockito.ArgumentMatchers.eq(31), any(TeamMemberRequest.class)))
            .thenReturn(team);

        mockMvc.perform(get("/api/teams"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Sales"));
        mockMvc.perform(get("/api/teams/31"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/teams")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sales\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(put("/api/teams/31")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Revenue\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/teams/31/archive"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/teams/31/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":22,\"role\":\"member\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/teams/31/members/22"))
            .andExpect(status().isNoContent());

        verify(service).removeMember(31, 22);
        mockMvc.perform(post("/api/teams")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTeamAndMissingPermissionRemainDistinct() throws Exception {
        when(service.get(404)).thenThrow(new ResourceNotFoundException("Team not found"));
        doThrow(new ForbiddenException("Requires TEAM_MANAGE permission in this workspace"))
            .when(service).removeMember(31, 22);

        mockMvc.perform(get("/api/teams/404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/teams/31/members/22"))
            .andExpect(status().isForbidden());
    }
}

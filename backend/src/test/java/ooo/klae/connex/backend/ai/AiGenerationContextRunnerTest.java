package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiGenerationContextRunnerTest {

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void reloadsAndPropagatesSecurityTenantAndLocaleContexts() {
        UserMapper userMapper = mock(UserMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        TenantCatalogResolver tenantCatalogResolver = mock(TenantCatalogResolver.class);
        TenantContext tenantContext = new TenantContext();
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
                tenantContext, tenantCatalogResolver, workspaceMapper);
        AutomationExecutor automationExecutor = new AutomationExecutor(
                tenantContext, mock(AutomationScope.class), tenantWorkScope);
        User principal = new User();
        principal.setId(42);
        when(userMapper.getUserById(42)).thenReturn(principal);
        when(workspaceService.getRole(7, 42)).thenReturn("member");
        when(workspaceMapper.getOrgId(7)).thenReturn(11);
        when(tenantCatalogResolver.resolveCatalog(11)).thenReturn("cnx_org_11");
        AiGenerationContextRunner runner = new AiGenerationContextRunner(
                userMapper, workspaceService, automationExecutor);

        runner.run(7, 42, Locale.JAPAN, () -> {
            assertSame(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertEquals(7, tenantContext.getWorkspaceId());
            assertEquals(11, tenantContext.getOrgId());
            assertEquals("cnx_org_11", tenantContext.getCatalog());
            assertEquals(Locale.JAPAN, LocaleContextHolder.getLocale());
        });

        assertFalse(tenantContext.isResolved());
    }
}

package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Pins that the command centre reports availability from the fact that actually decides whether a
 * scheduled brief can run, rather than from the build's declared catalog alone.
 */
class AiCommandCenterServiceTest {

    private final AiBriefScheduleService scheduleService = mock(AiBriefScheduleService.class);
    private final AiWatchService watchService = mock(AiWatchService.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private final AiCommandCenterService service = new AiCommandCenterService(
            scheduleService, watchService, new AiSkillCatalog(), featureGate, workspaceService);

    @BeforeEach
    void installReads() {
        when(scheduleService.current()).thenReturn(null);
        when(watchService.list()).thenReturn(List.of());
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("UTC");
    }

    @Test
    void aWorkspaceWithAUsableAssistantCanScheduleABrief() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);

        assertTrue(service.get().briefSkillAvailable());
    }

    /**
     * Reporting the catalog alone would render an enabled schedule switch in a workspace whose runs
     * are guaranteed to skip — a control that promises a brief which silently never arrives.
     */
    @Test
    void aWorkspaceWhoseAssistantIsSwitchedOffReportsBriefsUnavailable() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);

        assertFalse(service.get().briefSkillAvailable());
    }

    /**
     * The two sections answer to different facts and the surface says so. A workspace that has not
     * configured a provider cannot generate a brief, but its watches are decided entirely by the
     * warmth, task, and deal-risk models and keep working — reporting one flag for both would hide a
     * feature that is running perfectly well.
     */
    @Test
    void aWorkspaceWithoutAProviderStillReportsWatchesAvailable() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);
        when(featureGate.isFeatureGoverned(AiFeature.ASSISTANT_CHAT)).thenReturn(true);

        assertFalse(service.get().briefSkillAvailable());
        assertTrue(service.get().watchesAvailable());
    }

    /** Switching the assistant off stops both, because governance binds the deterministic half too. */
    @Test
    void aWorkspaceWhoseAssistantIsSwitchedOffReportsWatchesUnavailableToo() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);
        when(featureGate.isFeatureGoverned(AiFeature.ASSISTANT_CHAT)).thenReturn(false);

        assertFalse(service.get().briefSkillAvailable());
        assertFalse(service.get().watchesAvailable());
    }
}

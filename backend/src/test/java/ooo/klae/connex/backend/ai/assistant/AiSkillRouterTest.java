package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

class AiSkillRouterTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final List<AiChatPageContextDto> COMPANY_CONTEXT =
            List.of(new AiChatPageContextDto("company", 12));

    private WorkspaceService workspaceService;
    private AiSkillRouter router;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.permissionsFor(anyInt(), anyInt()))
                .thenReturn(Set.of(Permission.AI_USE));
        router = new AiSkillRouter(new AiSkillCatalog(), workspaceService);
    }

    @Test
    void theStagingBatteryQuestionRoutesToTheBoundedActivityDigest() {
        AiSkillRouter.Routing routing = route(
                "List recent activities for cool or cold accounts.", List.of());

        assertTrue(routing.routed());
        assertEquals("activity_digest_v1", routing.skill().key());
        assertEquals(AiSkillRouter.MATCHED, routing.reason());
    }

    @Test
    void theJapaneseActivityDigestRequestRoutesToTheSameSkill() {
        AiSkillRouter.Routing routing = route("最近の活動をまとめて教えてください。", List.of());

        assertTrue(routing.routed());
        assertEquals("activity_digest_v1", routing.skill().key());
    }

    @Test
    void aCoolingQuestionAboutTheOpenRecordRoutesToTheCoolingExplanation() {
        AiSkillRouter.Routing routing = route(
                "Why is this relationship cooling?", COMPANY_CONTEXT);

        assertTrue(routing.routed());
        assertEquals("relationship_cooling_explanation_v1", routing.skill().key());
        assertNotNull(routing.subject());
        assertEquals("company", routing.subject().kind());
        assertEquals(12, routing.subject().id());
    }

    @Test
    void theJapaneseCoolingQuestionRoutesToTheCoolingExplanation() {
        AiSkillRouter.Routing routing = route("この関係が冷めてきたのはなぜですか。", COMPANY_CONTEXT);

        assertTrue(routing.routed());
        assertEquals("relationship_cooling_explanation_v1", routing.skill().key());
    }

    @Test
    void aBriefRequestRoutesToTheRelationshipBrief() {
        AiSkillRouter.Routing routing = route("Tell me about this account.", COMPANY_CONTEXT);

        assertTrue(routing.routed());
        assertEquals("relationship_brief_v1", routing.skill().key());
    }

    @Test
    void aPipelineAttentionQuestionRoutesToThePipelineReviewWithoutAnyRecordContext() {
        AiSkillRouter.Routing routing = route("Which open deals need attention?", List.of());

        assertTrue(routing.routed());
        assertEquals("pipeline_attention_review_v1", routing.skill().key());
        assertNull(routing.subject());
    }

    @Test
    void theJapanesePipelineAttentionQuestionRoutesToThePipelineReview() {
        AiSkillRouter.Routing routing = route("リスクのある案件を教えてください。", List.of());

        assertTrue(routing.routed());
        assertEquals("pipeline_attention_review_v1", routing.skill().key());
    }

    @Test
    void aSubjectSkillWithoutAnAnchoringRecordFallsBackRatherThanRefusing() {
        AiSkillRouter.Routing routing = route("Why is this relationship cooling?", List.of());

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.MISSING_CONTEXT, routing.reason());
    }

    @Test
    void anAnchoringRecordOfTheWrongKindDoesNotSatisfyASubjectSkill() {
        AiSkillRouter.Routing routing = route(
                "Why is this relationship cooling?",
                List.of(new AiChatPageContextDto("deal", 31)));

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.MISSING_CONTEXT, routing.reason());
    }

    /**
     * A briefing verb is not a briefing request. These questions each name a specific field, time,
     * or figure, and a relationship brief on a three-step synthesis budget would answer none of
     * them; the generic loop can.
     */
    @Test
    void aQuestionAboutOneFieldIsNotHijackedIntoAWholeRelationshipBrief() {
        AiSkillRouter.Routing routing = route(
                "Tell me about the pricing on this deal.", COMPANY_CONTEXT);

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.NO_MATCH, routing.reason());
    }

    @Test
    void theJapaneseFieldQuestionIsAlsoLeftToTheGenericLoop() {
        AiSkillRouter.Routing routing = route(
                "この商談の価格について教えてください。", COMPANY_CONTEXT);

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.NO_MATCH, routing.reason());
    }

    @Test
    void aSchedulingQuestionIsLeftToTheGenericLoopInBothLocales() {
        assertFalse(route(
                "Do I have any scheduling conflicts on Thursday afternoon?",
                COMPANY_CONTEXT).routed());
        assertFalse(route(
                "木曜日の午後に予定の重複はありますか。", COMPANY_CONTEXT).routed());
    }

    @Test
    void aNovelAnalyticalQuestionIsLeftToTheGenericLoopInBothLocales() {
        assertFalse(route(
                "How does our win rate compare with the previous quarter?",
                COMPANY_CONTEXT).routed());
        assertFalse(route(
                "前四半期と比べて受注率はどう変わりましたか。", COMPANY_CONTEXT).routed());
    }

    @Test
    void aNovelQuestionIsLeftToTheBoundedGenericLoop() {
        AiSkillRouter.Routing routing = route(
                "Draft a renewal proposal in Japanese.", COMPANY_CONTEXT);

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.NO_MATCH, routing.reason());
    }

    @Test
    void aMemberWithoutTheAssistantPermissionNeverReachesASkill() {
        when(workspaceService.permissionsFor(anyInt(), anyInt())).thenReturn(Set.of());

        AiSkillRouter.Routing routing = route("Which open deals need attention?", List.of());

        assertFalse(routing.routed());
        assertEquals(AiSkillRouter.PERMISSION_DENIED, routing.reason());
    }

    @Test
    void anUnconstrainedCohortRequestAsksForAScopePreviewFirst() {
        AiSkillRouter.Routing routing = route(
                "List recent activities across the workspace.", List.of());

        assertTrue(routing.routed());
        assertTrue(routing.previewRecommended());
    }

    @Test
    void aDeclaredCohortScopeAlreadyStatesItsBreadthAndSkipsThePreview() {
        AiChatQueryScope scope = new AiChatQueryScope(
                true, null, null, 90, ooo.klae.connex.backend.dto.MemberScope.allTeam(),
                List.of("cool", "cold"), List.of("company"), List.of(), List.of(),
                List.of(), null);

        AiSkillRouter.Routing routing = router.route(
                WORKSPACE_ID, USER_ID,
                "List recent activities across the workspace.", List.of(), scope);

        assertTrue(routing.routed());
        assertFalse(routing.previewRecommended());
    }

    @Test
    void blankRequestsAreNeverRouted() {
        assertFalse(route("   ", COMPANY_CONTEXT).routed());
        assertFalse(route(null, COMPANY_CONTEXT).routed());
    }

    /**
     * The personal work brief is matched before the relationship brief, which is only safe while its
     * triggers stay strictly narrower. These pin that: the bare personal forms — including the two
     * literal sentences a scheduled run sends — must reach it, and the same words followed by a
     * record must not.
     */
    @Test
    void theBarePersonalBriefPhrasingsRouteToTheWorkBriefInBothLocales() {
        for (String request : List.of(
                "Give me my daily brief.",
                "Give me my weekly review.",
                "What should I focus on today?",
                "What's on my plate?",
                "今日のブリーフ",
                "今日のやることをまとめてください。",
                "今週のまとめを教えてください。")) {
            AiSkillRouter.Routing routing = route(request, List.of());
            assertTrue(routing.routed(), request + " must route");
            assertEquals("daily_work_brief_v1", routing.skill().key(), request);
        }
    }

    @Test
    void aRecordScopedPhrasingIsNeverSwallowedByTheSubjectlessWorkBrief() {
        for (String request : List.of(
                "Give me today's summary of Acme.",
                "What should I focus on with my deals today?",
                "今日のAcme社のまとめを教えてください。")) {
            AiSkillRouter.Routing routing = route(request, COMPANY_CONTEXT);
            assertFalse(
                    routing.routed() && "daily_work_brief_v1".equals(routing.skill().key()),
                    request + " names a record and must not become a subject-less day plan");
        }
    }

    private AiSkillRouter.Routing route(String text, List<AiChatPageContextDto> context) {
        return router.route(
                WORKSPACE_ID, USER_ID, text, context, AiChatQueryScope.none());
    }
}

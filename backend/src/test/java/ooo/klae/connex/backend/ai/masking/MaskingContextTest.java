package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MaskingContextTest {
    private static final String EXHAUSTED_NAME =
            "Johnathan " + "[".repeat(17) + "Sm" + "](person:1)".repeat(17) + "ith";

    @Test
    void shortNonAsciiMemberSymbolsAreIrreversibleWithoutRegisteringAsciiPunctuation() {
        for (String member : List.of("😀", "♞", "🛰️")) {
            var context = new MaskingContext();
            context.registerMemberCandidate(member);
            assertTrue(context.identifierDictionary().contains(member));
            assertEquals(List.of(member), context.memberCandidatesIn("Ask " + member + "."));
            assertEquals("Ask " + MaskingEngine.REDACTED + ".", MaskingEngine.maskFreeText("Ask " + member + ".", context));
            assertEquals(MaskingEngine.REDACTED, context.tokenFor(EntityKind.MEMBER, member));
            assertTrue(context.tokenBindings().isEmpty());
            context.exposeDispatchedPayload("{{M1}}");
            assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        }
        var punctuation = new MaskingContext();
        punctuation.registerMemberCandidate(".");
        punctuation.registerMemberCandidate("-");
        punctuation.registerMemberCandidate("?");
        assertTrue(punctuation.identifierDictionary().isEmpty());
        assertEquals("Ask. Then follow-up?", MaskingEngine.maskFreeText("Ask. Then follow-up?", punctuation));
    }

    @Test
    void checkpointsRestoreAllProtectionKindsIndependentlyOfTokenIdentity() {
        for (EntityKind kind : EntityKind.values()) {
            if (kind == EntityKind.MEMBER) {
                continue;
            }
            var context = new MaskingContext();
            String memberToken = context.tokenFor(EntityKind.MEMBER, "Acme");
            var memberOnly = context.checkpoint();
            assertEquals(memberToken, context.tokenFor(kind, "Acme"));
            assertTrue(context.hasNonMemberIdentifier("Acme"));
            assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText("AcmeCorp", context));
            var combined = context.checkpoint();
            context.registerMemberCandidate("Other Member");
            context.restore(combined);
            assertTrue(context.hasNonMemberIdentifier("Acme"));
            context.restore(memberOnly);
            assertFalse(context.hasNonMemberIdentifier("Acme"));
            assertEquals("AcmeCorp", MaskingEngine.maskFreeText("AcmeCorp", context));
            assertEquals(List.of(Map.entry(memberToken, "Acme")), context.tokenBindings());
            assertEquals(1, Demasker.demask(memberToken, context).warnings());
        }
    }

    @Test
    void restoringBeforeMemberRegistrationRetainsTheEarlierNonMemberProtection() {
        var context = new MaskingContext();
        String company = context.tokenFor(EntityKind.COMPANY, "Acme");
        var checkpoint = context.checkpoint();
        context.registerMemberCandidate("Acme");
        context.restore(checkpoint);
        assertFalse(context.isMemberIdentifier("Acme"));
        assertTrue(context.hasNonMemberIdentifier("Acme"));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskFreeText("AcmeCorp", context));
        assertEquals(List.of(Map.entry(company, "Acme")), context.tokenBindings());
    }

    @Test
    void aSymbolMemberCannotReuseAnEarlierOtherKindBindingAndCheckpointRestoresTheOldKind() {
        var context = new MaskingContext();
        String token = context.tokenFor(EntityKind.PERSON, "😀");
        var checkpoint = context.checkpoint();
        context.registerMemberCandidate("😀");
        context.exposeDispatchedPayload(token);
        assertEquals(1, Demasker.demask(token, context).warnings());
        assertEquals(MaskingEngine.REDACTED, context.tokenFor(EntityKind.MEMBER, "😀"));
        context.restore(checkpoint);
        assertEquals("😀", Demasker.demask(token, context).text());
    }

    /**
     * A restore withdraws exactly the bindings issued after its checkpoint, and a withdrawn
     * placeholder is never issued again for another value.
     *
     * <p>The binding made before the checkpoint keeps its placeholder and still demasks. The
     * withdrawn placeholders demask as invented ones, their raw values leave the identifier
     * dictionary and the seeded set, and a value seeded again after the restore takes a fresh
     * placeholder rather than a withdrawn one, because the abandoned calls' durable rows may still
     * carry the withdrawn placeholders.
     */
    @Test
    void aRestoreWithdrawsExactlyTheBindingsIssuedAfterItsCheckpoint() {
        MaskingContext context = new MaskingContext();
        String kept = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        List<Map.Entry<String, String>> bindings = context.tokenBindings();
        Set<String> dictionary = Set.copyOf(context.identifierDictionary());
        MaskingContext.Checkpoint checkpoint = context.checkpoint();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Jane Roe", context);
        String company = MaskingEngine.maskField(EntityKind.COMPANY, "Northwind Labs", context);
        assertEquals(MaskingEngine.REDACTED,
                MaskingEngine.maskField(EntityKind.PERSON, EXHAUSTED_NAME, context));
        assertTrue(context.isUnsafeIdentifierValue(EXHAUSTED_NAME));

        context.restore(checkpoint);

        assertEquals(bindings, context.tokenBindings());
        assertEquals(dictionary, context.identifierDictionary());
        assertEquals(new Demasker.DemaskResult("Mina Patel", 0), Demasker.demask(kept, context));
        assertEquals(1, Demasker.demask(person, context).warnings());
        assertEquals(1, Demasker.demask(company, context).warnings());
        assertFalse(context.isSeededIdentifierValue("Northwind Labs"));
        assertFalse(context.isUnsafeIdentifierValue(EXHAUSTED_NAME));
        assertEquals(kept, MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context));
        assertNotEquals(person, MaskingEngine.maskField(EntityKind.PERSON, "Jane Roe", context));
        assertEquals(1, Demasker.demask(person, context).warnings());
    }

    /**
     * A checkpoint restores only the context that issued it, and never past a binding an earlier
     * restore already withdrew.
     */
    @Test
    void aCheckpointRestoresOnlyTheContextThatIssuedItAndNeverForward() {
        MaskingContext context = new MaskingContext();
        MaskingContext.Checkpoint empty = context.checkpoint();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskingContext.Checkpoint one = context.checkpoint();

        assertThrows(IllegalArgumentException.class, () -> new MaskingContext().restore(empty));
        context.restore(empty);
        assertThrows(IllegalArgumentException.class, () -> context.restore(one));
        assertEquals(List.of(), context.tokenBindings());
        assertEquals(1, Demasker.demask(person, context).warnings());
    }
    @org.junit.jupiter.api.Test
    void memberCandidatesAllocateOnlyOnMatchingTextAndKeepCrossKindIdentity() {
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate("Unused Member");
        context.registerMemberCandidate("山田 太郎");
        context.registerMemberCandidate("山田 太郎");
        assertEquals(0, context.tokenBindings().size());
        assertEquals("Ask {{M1}}", MaskingEngine.maskFreeText("Ask 山田 太郎", context));
        assertEquals("{{M1}}", context.tokenFor(EntityKind.PERSON, "山田 太郎"));
        assertEquals(1, context.tokenBindings().size());
        assertEquals(2, context.identifierDictionary().size());
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        context.exposeDispatchedPayload("{\"content\":\"{{M1}}\"}");
        assertEquals("山田 太郎", Demasker.demask("{{ M1 }}", context).text());
        assertEquals(1, Demasker.demask("{{M2}}", context).warnings());
    }

    @org.junit.jupiter.api.Test
    void candidateBindingRestoreLeavesTheCandidateAndNeverReusesTheWithdrawnToken() {
        MaskingContext context = new MaskingContext();
        context.registerMemberCandidate("Prior Member");
        MaskingContext.Checkpoint checkpoint = context.checkpoint();
        assertEquals("{{M1}}", MaskingEngine.maskFreeText("Prior Member", context));
        context.restore(checkpoint);
        assertEquals(0, context.tokenBindings().size());
        assertEquals("{{M2}}", MaskingEngine.maskFreeText("Prior Member", context));
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
    }

    @org.junit.jupiter.api.Test
    void unmaskedMemberCandidatesDoNotIssueBindings() {
        MaskingContext context = new MaskingContext(ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED);
        context.registerMemberCandidate("Current Member");
        assertEquals("Current Member", MaskingEngine.maskFreeText("Current Member", context));
        assertEquals(0, context.tokenBindings().size());
        assertTrue(context.identifierDictionary().isEmpty());
        assertEquals(List.of("Current Member"), context.memberCandidatesIn("Ask Current Member to follow up"));
        context.registerMemberCandidate("Li");
        assertEquals(List.of(), context.memberCandidatesIn("Open client pipeline"));
        assertEquals(List.of("Li"), context.memberCandidatesIn("Ask Li_team"));
    }

    @Test
    void memberProvenanceSurvivesAnExactValueFirstIssuedByAnotherKind() {
        MaskingContext context = new MaskingContext();
        assertEquals("{{P1}}", context.tokenFor(EntityKind.PERSON, "Shared Label"));
        context.registerMemberCandidate("Shared Label");
        assertEquals("{{P1}}", context.tokenFor(EntityKind.MEMBER, "Shared Label"));
        assertEquals(List.of(), demaskedMembers(context, "{{P1}} handled it"));
        assertEquals(1, Demasker.demask("{{P1}}", context).warnings());
        context.exposeDispatchedPayload("{\"content\":\"{{P1}}\"}");
        assertEquals(List.of("Shared Label"), demaskedMembers(context, "{{P1}} handled it"));
        assertEquals(List.of(), demaskedMembers(context, "A different answer"));
    }

    @Test
    void memberProvenanceRecognizesAnExactValueDispatchedBeforeTheMemberReferenceWasRead() {
        MaskingContext context = new MaskingContext();
        assertEquals("{{P1}}", context.tokenFor(EntityKind.PERSON, "Earlier Label"));
        context.exposeDispatchedPayload("{\"content\":\"{{P1}}\"}");
        context.registerMemberCandidate("Earlier Label");
        assertEquals(List.of("Earlier Label"), demaskedMembers(context, "{{P1}} handled it"));
        assertEquals(0, Demasker.demask("{{P1}}", context).warnings());
    }

    private static List<String> demaskedMembers(MaskingContext context, String text) {
        var tree = new tools.jackson.databind.ObjectMapper().createObjectNode().put("text", text);
        var members = new java.util.ArrayList<String>();
        Demasker.demaskTree(tree, context, (path, member) -> members.add(member));
        return members;
    }

    @Test
    void placeholderShapedIdentifiersNeverAllocateOrAuthorizeReversibleTokens() {
        for (String label : List.of("{{M1}}", "{{ M2 }}", "｛｛Ｍ１｝｝", "{{m1}}", "Team {{M1}}", "{{P1}}")) {
            var context = new MaskingContext();
            context.registerMemberCandidate(label);
            assertEquals(MaskingEngine.REDACTED, context.tokenFor(EntityKind.MEMBER, label), label);
            assertEquals(List.of(), context.tokenBindings());
            assertEquals("Ask " + MaskingEngine.REDACTED, MaskingEngine.maskFreeText("Ask " + label, context).replaceAll(" +", " ").strip(), label);
            context.exposeDispatchedPayload("{{M1}} {{M2}} {{P1}}");
            assertEquals(1, Demasker.demask("{{M1}}", context).warnings(), label);
            OutboundLeakScan.assertNoLeakInServerEnvelope("{\"content\":\"[redacted]\"}", context,
                    new tools.jackson.databind.ObjectMapper());
            assertThrows(MaskingLeakException.class, () -> OutboundLeakScan.assertNoLeakInServerEnvelope(
                    new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("content", label)), context,
                    new tools.jackson.databind.ObjectMapper()), label);
        }
        for (EntityKind kind : EntityKind.values()) {
            var context = new MaskingContext();
            assertEquals(MaskingEngine.REDACTED, context.tokenFor(kind, "{{" + kind.tokenPrefix() + "1}}"));
            assertTrue(context.tokenBindings().isEmpty());
        }
    }

    @Test
    void placeholderReservationsSurviveLateCollisionsAndRestoreWithTheirCheckpoint() {
        var context = new MaskingContext();
        assertEquals("{{M1}}", context.tokenFor(EntityKind.MEMBER, "First Member"));
        context.exposeDispatchedPayload("{{M1}}");
        var checkpoint = context.checkpoint();
        context.registerMemberCandidate("{{M1}}");
        assertEquals(1, Demasker.demask("{{M1}}", context).warnings());
        assertEquals(MaskingEngine.REDACTED, context.tokenFor(EntityKind.MEMBER, "First Member"));
        context.registerMemberCandidate("{{M2}}");
        assertEquals("{{M3}}", context.tokenFor(EntityKind.MEMBER, "Second Member"));
        context.restore(checkpoint);
        assertEquals("First Member", Demasker.demask("{{M1}}", context).text());
        assertEquals("{{M4}}", context.tokenFor(EntityKind.MEMBER, "Third Member"));
        assertEquals(1, Demasker.demask("{{M3}}", context).warnings());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = EntityKind.class, names = {"PERSON", "COMPANY", "DEAL", "EMAIL", "PHONE"})
    void rawMemberRoleRequiresExposureEvenWhenAnotherKindOwnsTheToken(EntityKind kind) {
        var context = new MaskingContext();
        String token = context.tokenFor(kind, "Shared Member");
        assertEquals("Shared Member", Demasker.demask(token, context).text());
        context.registerMemberCandidate("Shared Member");
        assertEquals(1, Demasker.demask(token, context).warnings());
        assertEquals("[unknown reference]", Demasker.demask(token, context).text());
        assertTrue(context.containsUnexposedMemberToken(token));
        assertTrue(context.containsUnexposedMemberToken(token.toLowerCase(java.util.Locale.ROOT)));
        context.exposeDispatchedPayload(token);
        assertFalse(context.containsUnexposedMemberToken(token));
        assertEquals("Shared Member", Demasker.demask(token, context).text());
        assertTrue(context.containsUnexposedMemberToken(token.toLowerCase(java.util.Locale.ROOT)));
        var exposedFirst = new MaskingContext();
        String earlier = exposedFirst.tokenFor(kind, "Earlier Member");
        exposedFirst.exposeDispatchedPayload(earlier);
        exposedFirst.registerMemberCandidate("Earlier Member");
        assertEquals("Earlier Member", Demasker.demask(earlier, exposedFirst).text());
        String unrelated = context.tokenFor(kind, "Unrelated Record");
        assertEquals("Unrelated Record", Demasker.demask(unrelated, context).text());
        context.registerMemberCandidate(token);
        assertTrue(context.containsUnexposedMemberToken(token));
        assertTrue(context.containsUnexposedMemberToken(token.toLowerCase(java.util.Locale.ROOT)));
        assertEquals(1, Demasker.demask(token, context).warnings());
        assertEquals("Unrelated Record", Demasker.demask(unrelated, context).text());
    }

}

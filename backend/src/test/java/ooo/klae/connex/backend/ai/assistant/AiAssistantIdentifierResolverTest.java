package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

class AiAssistantIdentifierResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "[Former][m]\n\n[m]:\nuser:42", "[Former][m]\r\r[m]:\ruser:42",
            "[Former][m]\n\n> - [m]: user:42",
            "| ` | [Former][m] | ` |\n| --- | --- | --- |\n\n[m]: user:42",
            "See [^f].\n\n[^f]: [Former][m]\n\n[m]: user:42"
    })
    void queuedParserLayoutsRetainOriginalMemberLabelsBesideInvisibleTargets(String content) {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ReferenceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        when(references.visibleMemberIds(7, Set.of(42))).thenReturn(Set.of(42));
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);
        String original = "[Hidden](person:999 \"title\")\n\n" + content;

        var result = resolver.resolve(original);

        assertEquals(List.of(new AiAssistantIdentifierResolver.Identifier(EntityKind.MEMBER, "Former")), result.identifiers());
        assertTrue(result.resources().isEmpty());
        verify(references).visibleMemberIds(7, Set.of(42));
        verify(references, never()).redactInvisibleTargets(anyInt(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "blocks", "inlines", "table", "footnote", "brackets",
            "raw", "code", "disagreement", "same-id", "encoded", "label"})
    void incompleteQueueReadingsRefuseBeforeIdentifierLookupOrProviderAdmission(String kind) {
        String text = switch (kind) {
            case "input" -> "x".repeat(262_145) + "[Late](user:42)";
            case "blocks" -> "> ".repeat(100) + "[Former](user:42)";
            case "inlines" -> "**".repeat(100) + "Former" + "**".repeat(100);
            case "table" -> "| x ".repeat(258);
            case "footnote" -> "See [^a\\]b].\n\nprefix `\n[^a\\]b]: [Former](user:42 \"title\")\n    `";
            case "brackets" -> "[".repeat(131_000) + "[](u)".repeat(26_000);
            case "raw" -> "literal user:42";
            case "code" -> "```\n[Former](user:42)\n```";
            case "disagreement" -> "| h |\n| - |\n|\nprefix `\n[Former `]` Member](user:42 \"title\")\nsuffix `";
            case "same-id" -> "| h |\n| - |\n|\nprefix `\n[Former `]` Member](user:42 \"title\")\nsuffix `\n\n[unused]: user:42";
            case "encoded" -> "| h |\n| - |\n|\nprefix `\n[Former `]` Member](u&#115;er:42 \"title\")\nsuffix `";
            case "label" -> "[" + "a".repeat(1_000) + "](user:42)";
            default -> throw new IllegalArgumentException(kind);
        };
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ReferenceService.class);
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);

        var refused = assertThrows(AiAssistantLoopException.class, () -> resolver.resolve(text));

        assertEquals("identifier_text_unsafe", refused.detailReason());
        verifyNoInteractions(mapper, workspace, scope, references);
    }

    @Test
    void incompleteReaderAuthorizedProjectionsAlsoRefuseAndRetainCompatibilityCandidates() {
        String text = "x".repeat(262_145) + "[Late](user:42)";
        var reading = ReferenceService.memberReferenceReading(text);
        assertFalse(reading.complete());
        assertEquals(List.of(new ReferenceService.MemberReference("Late", Set.of(42))), reading.references());
        var context = new MaskingContext();

        assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.seedReferenceText(text, context));
        assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        assertTrue(context.identifierDictionary().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "42x", ""})
    void unrepresentableMemberProvenanceRefusesBeforeLookupAndDirectAiProjection(String id) {
        String text = "[Former](user:" + id + ")";
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ReferenceService.class);
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);
        var context = new MaskingContext();

        assertEquals("identifier_text_unsafe", assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve(text)).detailReason());
        assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.seedReferenceText(text, context));
        assertThrows(ooo.klae.connex.backend.ai.masking.MaskingLeakException.class,
                () -> AiAssistantMemberIdentifiers.referenceIdentifiers(text));
        assertTrue(context.identifierDictionary().isEmpty());
        verifyNoInteractions(mapper, workspace, scope, references);
    }

    @Test
    void unrepresentableNonMemberTargetsDoNotSuppressAuthorizableMemberProvenance() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ReferenceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        when(references.visibleMemberIds(7, Set.of(42))).thenReturn(Set.of(42));
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);

        var result = resolver.resolve("[Hidden](person:2147483648) [Former](user:42)");

        assertEquals(List.of(new AiAssistantIdentifierResolver.Identifier(EntityKind.MEMBER, "Former")), result.identifiers());
        assertTrue(result.resources().isEmpty());
        verify(references).visibleMemberIds(7, Set.of(42));
        verify(references, never()).redactInvisibleTargets(anyInt(), anyList());
    }

    @Test
    void queuedReferencesRegisterAuthorizedAuthoredAndDecodedLabelsWithoutMemberHandles() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ooo.klae.connex.backend.services.ReferenceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        var member = new ooo.klae.connex.backend.beans.User();
        member.setDisplayName("Alice & Bob");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        String authorized = "[Former Name](user:42) [Alice &amp; Bob](user:42) [Pending Alias](user:43)";
        String text = authorized + " [Foreign Alias](user:99)";
        when(references.visibleMemberIds(7, Set.of(42, 43, 99))).thenReturn(Set.of(42, 43));
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);
        var result = resolver.resolve(text);
        assertTrue(result.resources().isEmpty());
        assertEquals(java.util.Set.of("Former Name", "Alice & Bob", "Alice &amp; Bob", "Pending Alias"),
                result.identifiers().stream().map(AiAssistantIdentifierResolver.Identifier::value)
                        .collect(Collectors.toSet()));
        var context = new MaskingContext();
        resolver.seed(result, context);
        assertTrue(context.tokenBindings().isEmpty());
        assertFalse(context.identifierDictionary().contains("Foreign Alias"));
        verify(references).visibleMemberIds(7, Set.of(42, 43, 99));
    }

    @Test
    void queuedReferenceLabelsDeduplicateDirectoryMentionsAndRetainTheCombinedLimit() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var references = mock(ooo.klae.connex.backend.services.ReferenceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        when(references.visibleMemberIds(7, Set.of(42))).thenReturn(Set.of(42));
        var member = new ooo.klae.connex.backend.beans.User();
        member.setDisplayName("Alice");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);
        assertEquals(1, resolver.resolve("Ask Alice [Alice](user:42)").identifiers().size());
        String twenty = IntStream.range(0, 20).mapToObj(index -> "[Former " + index + "](user:42)")
                .collect(Collectors.joining(" "));
        assertEquals(20, resolver.resolve(twenty).identifiers().size());
        var refused = assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve(twenty + " [Former 20](user:42)"));
        assertEquals("identifier_limit_exceeded", refused.detailReason());
    }

    @Test
    void queuedFormerLabelSurvivesAnInvisibleTitledPersonReference() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        var member = new User();
        member.setId(42);
        member.setDisplayName("Current Name");
        when(workspace.getMembers(7)).thenReturn(List.of(member));
        var members = mock(WorkspaceMapper.class);
        when(members.getMemberIdsIncludingPending(7, List.of(42))).thenReturn(List.of(42));
        var references = memberReferences(workspace, members);
        String text = "[Former Name](user:42) [x](person:999 \"title\")";
        assertEquals("(unavailable reference)", references.redactInvisibleTargets(7,
                List.of(new ReferenceService.ReaderVisibleContent(text, List.of()))).getFirst().content());

        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);
        var result = resolver.resolve(text);

        assertEquals(List.of(new AiAssistantIdentifierResolver.Identifier(EntityKind.MEMBER, "Former Name")),
                result.identifiers());
        var context = new MaskingContext();
        resolver.seed(result, context);
        assertTrue(context.tokenBindings().isEmpty());
        assertTrue(context.identifierDictionary().contains("Former Name"));
        assertFalse(MaskingEngine.maskConversationalFreeText(text, context).contains("Former Name"));
    }

    @Test
    void queuedMixedReferencesAuthorizeEachMemberIndependently() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        var members = mock(WorkspaceMapper.class);
        when(members.getMemberIdsIncludingPending(eq(7), anyList())).thenReturn(List.of(42, 43));
        var people = mock(PersonMapper.class);
        when(people.getVisibleIdsIn(7, List.of(1))).thenReturn(List.of(1));
        var references = new ReferenceService(mock(EntityReferenceMapper.class), workspace, people,
                mock(DealMapper.class), mock(CompanyMapper.class), mock(NoteMapper.class),
                mock(AttachmentMapper.class), mock(TaskMapper.class), mock(ActivityMapper.class), members);
        String text = "[First Alias](user&colon;42) [Visible Contact](person:1) "
                + "[Foreign Alias](user:99) [Hidden Contact](person:999 \"title\") "
                + "[Second Alias][pending] [Third Alias](user\\:42)\n\n> [pending]:\n> user:43";
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, references);

        var result = resolver.resolve(text);

        assertEquals(List.of("First Alias", "Second Alias", "Third Alias"),
                result.identifiers().stream().map(AiAssistantIdentifierResolver.Identifier::value).toList());
        assertTrue(result.identifiers().stream().allMatch(identifier -> identifier.kind() == EntityKind.MEMBER));
        assertTrue(result.resources().isEmpty());
        verify(members).getMemberIdsIncludingPending(7, List.of(42, 99, 43));
        verify(workspace, never()).isMemberIncludingPending(anyInt(), anyInt());
        verifyNoInteractions(people);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 500, 1_000, 1_001})
    void queuedFakeMemberIdsUseOnlyBoundedVisibilityBatches(int count) {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var members = mock(WorkspaceMapper.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        List<List<Integer>> batches = new ArrayList<>();
        when(members.getMemberIdsIncludingPending(eq(7), anyList())).thenAnswer(call -> {
            List<Integer> ids = call.getArgument(1);
            batches.add(List.copyOf(ids));
            return List.of();
        });
        String text = IntStream.rangeClosed(1, count).mapToObj(id -> "[x](user:" + id + ")")
                .collect(Collectors.joining(" "));
        assertTrue(text.length() <= 16_000);
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, memberReferences(workspace, members));

        var result = resolver.resolve(text);

        assertEquals(List.of(), result.identifiers());
        assertEquals(List.of(), result.resources());
        verify(members, times((count + 499) / 500)).getMemberIdsIncludingPending(eq(7), anyList());
        assertTrue(batches.stream().allMatch(batch -> !batch.isEmpty() && batch.size() <= 500));
        assertEquals(IntStream.rangeClosed(1, count).boxed().toList(), batches.stream().flatMap(List::stream).toList());
        verify(workspace, never()).isMemberIncludingPending(anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedAmbiguousReferencesCollectTheLabelWhenAnyMemberTargetIsVisible(boolean visible) {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var members = mock(WorkspaceMapper.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        when(members.getMemberIdsIncludingPending(7, List.of(99, 42, 43)))
                .thenReturn(visible ? List.of(42) : List.of());
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, memberReferences(workspace, members));

        var result = resolver.resolve("[Former Name][m]\n\n[m]: user:99\n[m]: user:42\n[m]: user:43");

        assertEquals(visible ? List.of(new AiAssistantIdentifierResolver.Identifier(EntityKind.MEMBER, "Former Name"))
                : List.of(), result.identifiers());
        verify(members).getMemberIdsIncludingPending(7, List.of(99, 42, 43));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedLargeAmbiguousGroupRetainsTheIdentifierLimitWithBoundedVisibility(boolean visible) {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        var members = mock(WorkspaceMapper.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        when(members.getMemberIdsIncludingPending(eq(7), anyList())).thenAnswer(call -> {
            List<Integer> ids = call.getArgument(1);
            return visible && ids.contains(1_599) ? List.of(1_599) : List.of();
        });
        String text = IntStream.range(0, 600).mapToObj(index -> "[L" + index + "][m]")
                .collect(Collectors.joining(" ")) + "\n\n"
                + IntStream.range(1_000, 1_600).mapToObj(id -> "[m]: user:" + id).collect(Collectors.joining("\n"));
        assertTrue(text.length() <= 16_000);
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, memberReferences(workspace, members));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            if (visible) {
                var refused = assertThrows(AiAssistantLoopException.class, () -> resolver.resolve(text));
                assertEquals("identifier_limit_exceeded", refused.detailReason());
            } else {
                assertTrue(resolver.resolve(text).identifiers().isEmpty());
            }
        });
        verify(members, times(2)).getMemberIdsIncludingPending(eq(7), anyList());
    }

    private static ReferenceService memberReferences(WorkspaceService workspace, WorkspaceMapper members) {
        return new ReferenceService(mock(EntityReferenceMapper.class), workspace, mock(PersonMapper.class),
                mock(DealMapper.class), mock(CompanyMapper.class), mock(NoteMapper.class),
                mock(AttachmentMapper.class), mock(TaskMapper.class), mock(ActivityMapper.class), members);
    }

    @Test
    void exhaustedTurnProjectionIsMalformedBeforeLookupEvenWithNoCandidates() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        String message = "Johnathan " + "[".repeat(17) + "Sm" + "](person:1)".repeat(17) + "ith";

        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve(message));

        assertEquals("malformed_output", exception.terminalReason());
        assertEquals("identifier_text_unsafe", exception.detailReason());
        verifyNoInteractions(mapper);
    }

    @Test
    void exhaustedCandidateProjectionDoesNotBlockUnrelatedMentions() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        String name = "Johnathan " + "[".repeat(17) + "Sm" + "](person:1)".repeat(17) + "ith";
        when(mapper.findMentionedRecords(7, "[7]", "ask johnathan smith", 200, "", 0))
                .thenReturn(List.of(mention("person", 11, name), mention("person", 12, "Johnathan Smith")));

        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve("Ask Johnathan Smith");

        assertEquals(List.of(12), resolution.resources().stream().map(resource -> resource.id()).toList());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        assertEquals("Ask {{P1}}", MaskingEngine.maskConversationalFreeText("Ask Johnathan Smith", context));
    }

    @Test
    void unsafeStoredIdentifierStillResolvesByItsLiteralValueInConvergentText() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        String name = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
        String message = "[Visible](person:" + name + ")";
        String lookup = MaskingEngine.mentionScanText(message).lookupText();
        when(mapper.findMentionedRecords(7, "[7]", lookup, 200, "", 0))
                .thenReturn(List.of(mention("person", 11, name)));

        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(message);

        assertEquals(List.of(11), resolution.resources().stream().map(resource -> resource.id()).toList());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        assertEquals(List.of(name), List.copyOf(context.identifierDictionary()));
        assertEquals(MaskingEngine.REDACTED, MaskingEngine.maskTemporal(name, context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"John [O'Connor](person:999)", "[John](record:r1) O'Connor",
            "John [[O'Connor](person:1)](record:r2)"})
    void lookupAndAdmissionUseTheSameLinkedTurn(String spelling) {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        String prepared = MaskingEngine.mentionScanText("Ask " + spelling + " today.").lookupText();
        when(mapper.findMentionedRecords(7, "[7]", prepared, 200, "", 0))
                .thenReturn(List.of(mention("person", 11, "John O'Connor")));

        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve("Ask " + spelling + " today.");

        assertEquals(1, resolution.identifiers().size());
        assertEquals(11, resolution.resources().getFirst().id());
        verify(mapper).findMentionedRecords(7, "[7]", prepared, 200, "", 0);
    }

    @Test
    void lookupAndAdmissionUseTheSameDelimiterSanitizedTurn() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        String sanitized = "what is happening with john o'connor?";
        when(mapper.findMentionedRecords(7, "[7]", sanitized, 200, "", 0))
                .thenReturn(List.of(mention("person", 11, "John O'Connor")));

        AiAssistantIdentifierResolver.Resolution resolution =
                resolver.resolve("What is happening with John O'{{}}Connor?");

        assertEquals(1, resolution.identifiers().size());
        assertEquals("John O'Connor", resolution.identifiers().getFirst().value());
        verify(mapper).findMentionedRecords(7, "[7]", sanitized, 200, "", 0);
    }

    @Test
    void resolveUsesOneBoundedPageAndSeedsEveryMatchedKind() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        when(mapper.findMentionedRecords(7, "[7]", "ask ada about acme renewal", 200, "", 0))
                .thenReturn(List.of(
                        mention("person", 11, "Ada"),
                        mention("company", 12, "Acme"),
                        mention("deal", 13, "Acme Renewal")));

        AiAssistantIdentifierResolver.Resolution resolution =
                resolver.resolve("Ask Ada about Acme Renewal");
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        String masked = MaskingEngine.maskFreeText("Ask Ada about Acme Renewal", context);

        assertEquals(3, resolution.resources().size());
        assertFalse(masked.contains("Ada"));
        assertFalse(masked.contains("Acme"));
        verify(mapper).findMentionedRecords(7, "[7]", "ask ada about acme renewal", 200, "", 0);
    }

    @Test
    void resolveRejectsMoreBoundedMentionsThanTheTurnLimitAllows() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> mentions = IntStream.rangeClosed(1, 22)
                .mapToObj(id -> mention("person", id, "Mention%02d".formatted(id)))
                .toList();
        String message = "Ask about " + mentions.stream()
                .map(AiAssistantIdentifierMention::getValue)
                .collect(Collectors.joining(", "));
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), eq(""), eq(0)))
                .thenReturn(mentions);

        AiAssistantLoopException exception = assertThrows(
                AiAssistantLoopException.class,
                () -> resolver.resolve(message));

        assertEquals("identifier_limit_exceeded", exception.detailReason());
    }

    @Test
    void substringOnlyCandidatesBeyondTheTurnLimitStillResolveTheRealMention() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> candidates = new ArrayList<>(IntStream.rangeClosed(1, 30)
                .mapToObj(id -> mention("person", id, "id%02d".formatted(id)))
                .toList());
        String message = "Ask about Acme Renewal " + candidates.stream()
                .map(candidate -> "q" + candidate.getValue() + "q")
                .collect(Collectors.joining(" "));
        candidates.add(mention("person", 251, "Acme Renewal"));
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), eq(""), eq(0)))
                .thenReturn(List.copyOf(candidates));

        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(message);

        assertEquals(1, resolution.identifiers().size());
        assertEquals("Acme Renewal", resolution.identifiers().getFirst().value());
        assertEquals(1, resolution.resources().size());
        assertEquals(251, resolution.resources().getFirst().id());
    }

    /**
     * The prefilter admits every non-ASCII candidate without a containment test, so an ordinary
     * workspace whose records are named in a non-ASCII script returns its whole visible corpus for
     * every turn. Candidate volume must therefore never refuse a turn on its own.
     */
    @Test
    void aCorpusSizedCandidatePageStillResolvesTheOneGenuineMention() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> candidates = new ArrayList<>(IntStream.rangeClosed(1, 250)
                .mapToObj(id -> mention("person", id, "東京商事%03d".formatted(id)))
                .toList());
        candidates.add(mention("person", 251, "Acme Renewal"));
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), eq(""), eq(0)))
                .thenReturn(List.copyOf(candidates.subList(0, 200)));
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), eq("person"), eq(200)))
                .thenReturn(List.copyOf(candidates.subList(200, candidates.size())));

        for (String message : List.of("Ask about Acme Renewal", "今日のAcme Renewalの予定は？")) {
            AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(message);

            assertEquals(1, resolution.identifiers().size(), message);
            assertEquals("Acme Renewal", resolution.identifiers().getFirst().value(), message);
        }
    }

    @Test
    void admittedMentionsAcrossPagesStillEnforceTheTurnLimit() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> firstPage = new ArrayList<>(IntStream.rangeClosed(1, 199)
                .mapToObj(id -> mention("company", id, "東京商事%03d".formatted(id)))
                .toList());
        firstPage.add(mention("person", 1, "Acme"));
        List<AiAssistantIdentifierMention> secondPage = IntStream.rangeClosed(1, 20)
                .mapToObj(id -> mention("person", id + 1, "Renewal"))
                .toList();
        when(mapper.findMentionedRecords(7, "[7]", "ask acme about renewal", 200, "", 0))
                .thenReturn(firstPage);
        when(mapper.findMentionedRecords(7, "[7]", "ask acme about renewal", 200, "person", 1))
                .thenReturn(secondPage);

        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve("Ask Acme about Renewal"));

        assertEquals("identifier_limit_exceeded", exception.detailReason());
    }

    @Test
    void exactlyTwentyAdmittedMentionsAcrossPagesResolve() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> firstPage = new ArrayList<>(IntStream.rangeClosed(1, 199)
                .mapToObj(id -> mention("company", id, "東京商事%03d".formatted(id)))
                .toList());
        firstPage.add(mention("person", 1, "Acme"));
        List<AiAssistantIdentifierMention> secondPage = IntStream.rangeClosed(1, 19)
                .mapToObj(id -> mention("person", id + 1, "Renewal"))
                .toList();
        when(mapper.findMentionedRecords(7, "[7]", "ask acme about renewal", 200, "", 0))
                .thenReturn(firstPage);
        when(mapper.findMentionedRecords(7, "[7]", "ask acme about renewal", 200, "person", 1))
                .thenReturn(secondPage);

        assertEquals(20, resolver.resolve("Ask Acme about Renewal").identifiers().size());
    }

    @Test
    void fullPagesWithNoAdmittedMentionsContinueUntilTheMapperIsExhausted() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), anyString(), anyInt()))
                .thenAnswer(call -> {
                    int afterId = call.getArgument(5);
                    return afterId >= 50_200 ? List.of() : IntStream.rangeClosed(afterId + 1, afterId + 200)
                            .mapToObj(id -> mention("person", id, "東京商事%06d".formatted(id)))
                            .toList();
                });

        assertEquals(List.of(), resolver.resolve("No matching records").identifiers());
        verify(mapper).findMentionedRecords(7, "[7]", "no matching records", 200, "person", 50_200);
        verify(mapper, times(252)).findMentionedRecords(
                eq(7), eq("[7]"), eq("no matching records"), eq(200), anyString(), anyInt());
    }

    @Test
    void aFullCandidatePageIsGatedAgainstOneSeparatorFoldedReadingOfALongTurn() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        AiAssistantIdentifierResolver resolver = resolver(mapper);
        List<AiAssistantIdentifierMention> candidates = new ArrayList<>(IntStream.rangeClosed(1, 199)
                .mapToObj(id -> mention("person", id, "id%03d".formatted(id)))
                .toList());
        candidates.add(mention("deal", 99, "Acme\u0007Renewal"));
        String message = "Ask about Acme Renewal " + "filler ".repeat(2_000);
        when(mapper.findMentionedRecords(eq(7), eq("[7]"), anyString(), eq(200), eq(""), eq(0)))
                .thenReturn(List.copyOf(candidates));

        AiAssistantIdentifierResolver.Resolution resolution = assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> resolver.resolve(message));
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);

        assertEquals(1, resolution.identifiers().size());
        assertEquals("Acme\u0007Renewal", resolution.identifiers().getFirst().value());
        assertFalse(MaskingEngine.maskFreeText(message, context).contains("Acme Renewal"));
    }

    private static AiAssistantIdentifierResolver resolver(AiAssistantIdentifierMapper mapper) {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess =
                mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceScopeControlAccess.getForWorkspace(7))
                .thenReturn(new WorkspaceScope(2, List.of(7), "[7]"));
        return new AiAssistantIdentifierResolver(
                mapper, workspaceService, workspaceScopeControlAccess, mock(ooo.klae.connex.backend.services.ReferenceService.class));
    }

    private static AiAssistantIdentifierMention mention(String kind, int id, String value) {
        AiAssistantIdentifierMention mention = new AiAssistantIdentifierMention();
        mention.setKind(kind);
        mention.setId(id);
        mention.setValue(value);
        return mention;
    }
    @Test
    void shortMemberSubstringsNeitherCreateUserProvenanceNorConsumeTheMentionLimit() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var workspace = mock(WorkspaceService.class);
        var scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        String message = "show the client pipeline";
        List<String> labels = new ArrayList<>();
        labels.add("Li");
        IntStream.range(0, message.length() - 1).mapToObj(index -> message.substring(index, index + 2))
                .filter(value -> value.chars().allMatch(Character::isLetter) && !value.equalsIgnoreCase("Li")).distinct().forEach(labels::add);
        labels.addAll(List.of("cli", "ien", "ent", "pip", "ipe", "pel", "eli", "lin", "ine"));
        List<ooo.klae.connex.backend.beans.User> members = labels.stream().map(label -> {
            var member = new ooo.klae.connex.backend.beans.User();
            member.setDisplayName(label);
            return member;
        }).toList();
        when(workspace.getMembers(7)).thenReturn(members);
        var resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, mock(ooo.klae.connex.backend.services.ReferenceService.class));
        assertTrue(labels.stream().distinct().count() > 20);
        var unrelated = resolver.resolve(message);
        assertEquals(List.of(), unrelated.identifiers());
        var context = new MaskingContext();
        resolver.seed(unrelated, context);
        assertEquals(message, MaskingEngine.maskConversationalFreeText(message, context));
        var genuine = resolver.resolve("Ask Li to follow up");
        assertEquals(List.of(new AiAssistantIdentifierResolver.Identifier(
                ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Li")), genuine.identifiers());
        resolver.seed(genuine, context);
        assertEquals("Ask {{M1}} to follow up", MaskingEngine.maskConversationalFreeText("Ask Li to follow up", context));
    }

    @Test
    void memberMentionsShareTheTwentyIdentifierLimitWithoutResourceHandles() {
        AiAssistantIdentifierMapper mapper = mock(AiAssistantIdentifierMapper.class);
        WorkspaceService workspace = mock(WorkspaceService.class);
        when(workspace.getCurrentWorkspaceId()).thenReturn(7);
        OrganizationWorkspaceScopeControlAccess scope = mock(OrganizationWorkspaceScopeControlAccess.class);
        when(scope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        List<ooo.klae.connex.backend.beans.User> members = IntStream.rangeClosed(1, 21).mapToObj(index -> {
            var member = new ooo.klae.connex.backend.beans.User();
            member.setDisplayName("Member " + index);
            member.setUsername("username" + index);
            return member;
        }).toList();
        when(workspace.getMembers(7)).thenReturn(members);
        AiAssistantIdentifierResolver resolver = new AiAssistantIdentifierResolver(mapper, workspace, scope, mock(ooo.klae.connex.backend.services.ReferenceService.class));
        String twenty = members.subList(0, 20).stream().map(member -> member.getDisplayName())
                .collect(Collectors.joining(", "));
        var resolution = resolver.resolve(twenty);
        assertEquals(20, resolution.identifiers().size());
        assertEquals(List.of(), resolution.resources());
        assertEquals(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER,
                resolution.identifiers().getFirst().kind());
        var refusal = assertThrows(AiAssistantLoopException.class, () -> resolver.resolve(twenty + ", Member 21"));
        assertEquals("identifier_limit_exceeded", refusal.detailReason());
        var fallback = new ooo.klae.connex.backend.beans.User();
        fallback.setUsername("fallback-member");
        when(workspace.getMembers(7)).thenReturn(List.of(fallback, fallback));
        assertEquals(List.of("fallback-member"), AiAssistantMemberIdentifiers.labels(List.of(fallback, fallback)));
        assertEquals(1, resolver.resolve("Ask fallback-member").identifiers().size());
    }

}

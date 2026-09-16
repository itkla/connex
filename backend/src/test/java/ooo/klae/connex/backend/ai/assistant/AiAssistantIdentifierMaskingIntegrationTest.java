package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.framework;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiMediaAdmissionService;
import ooo.klae.connex.backend.ai.AiOrganizationBudgetCoordinator;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SegmentService;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the real identifier mapper through production prompt assembly and captures the first
 * provider request, so masking coverage is measured on the payload that would actually egress.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AiAssistantIdentifierMaskingIntegrationTest {
    @Autowired private AiAssistantIdentifierMapper identifierMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private WorkspaceMapper workspaceMapper;

    private Workspace workspace;
    private final List<Object> fixtureMocks = new ArrayList<>();
    private Optional<ProviderHarness> providerHarness = Optional.empty();
    private Optional<ScopeHarness> scopeHarness = Optional.empty();

    /** Releases request captures retained by Mockito's inline mock registry after each test. */
    @AfterEach
    void releaseFixtureMocks() {
        fixtureMocks.forEach(value -> framework().clearInlineMock(value));
        fixtureMocks.clear();
    }

    private <T> T fixtureMock(Class<T> type) {
        T value = mock(type);
        fixtureMocks.add(value);
        return value;
    }

    @BeforeEach
    void setUpWorkspace() {
        Organization organization = new Organization();
        organization.setName("Masking " + unique());
        organization.setSlug("masking-" + unique());
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setName("Masking " + unique());
        workspace.setSlug("masking-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
    }

    /**
     * Two deterministic base names cover 172 cases in one workspace. Every syntax fragment is
     * exercised on both operands through SQL admission, empty-context turns, the production cap
     * and provider capture; the provider and scope fixtures are reused for the complete batch.
     */
    @Test
    void generatedLinkFragmentsPreserveOriginalAdmissionAndCoverageThroughTheProvider() throws Exception {
        Company parent = newCompany("Fixture Parent");
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantIdentifierResolver resolver = resolver();
        int caseCount = 0;
        for (int seed = 0; seed < 2; seed++) {
            caseCount += verifyGeneratedLinkFragments(seed, parent, mapper, resolver);
        }
        assertEquals(172, caseCount);
    }

    private int verifyGeneratedLinkFragments(int seed, Company parent, ObjectMapper mapper,
            AiAssistantIdentifierResolver resolver) throws Exception {
        Random random = new Random(1658L + seed);
        String alphabet = "jkvzq";
        StringBuilder baseBuilder = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            if (index == 10) {
                baseBuilder.append(' ');
            }
            baseBuilder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        String base = baseBuilder.toString();
        List<LinkParityCase> cases = linkFragmentCases(base);
        int caseCount = cases.size();
        Person person = newPerson(parent, base);
        for (LinkParityCase sample : cases) {
            person.setName(sample.stored());
            personMapper.update(person);
            if (!sample.converged()) {
                assertUnsafeNestedTextRefused(person, sample.text(), mapper, resolver);
                continue;
            }
            String turn = "Ask " + sample.text() + " today.";
            MaskingEngine.MentionScanText scan = MaskingEngine.mentionScanText(turn);
            assertTrue(identifierMapper.findMentionedRecords(workspace.getId(), "[" + workspace.getId() + "]",
                    scan.lookupText(), 200, "", 0).stream().anyMatch(row -> row.getId() == person.getId()
                            && "person".equals(row.getKind())), sample.toString());
            assertTrue(MaskingEngine.containsIdentifierMention(scan, sample.stored()), sample.toString());
            AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
            assertTrue(resolution.resources().stream().anyMatch(resource -> resource.id() == person.getId()
                    && "person".equals(resource.kind())), sample.toString());
            MaskingContext turnContext = new MaskingContext();
            resolver.seed(resolution, turnContext);
            String turnPayload = mapper.writeValueAsString(firstProviderRequest(
                    invocation(turn, turnContext, mapper), mapper).messages());
            assertNoBaseFragments(base, turnPayload, sample);
            OutboundLeakScan.assertNoLeakStrict(turnPayload, turnContext, mapper);

            MaskingContext scopeContext = new MaskingContext();
            AiChatResourceRegistry resources = new AiChatResourceRegistry(scopeContext);
            AiAssistantToolResult scope = scopeResult(person, "x".repeat(498) + sample.text(), resources, mapper);
            String activities = mapper.writeValueAsString(scope.data().get("activities"));
            assertNoBaseFragments(base, activities, sample);
            AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, scopeContext,
                    new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                            List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                            List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", scope)),
                            scopeContext, resources), 256, 0.1);
            String scopePayload = mapper.writeValueAsString(firstProviderRequest(invocation, mapper).messages());
            assertNoBaseFragments(base, scopePayload, sample);
            OutboundLeakScan.assertNoLeakStrict(scopePayload, scopeContext, mapper);
        }
        person.setName(base);
        personMapper.update(person);
        Person literal = person;
        String bracketName = base.replace(" ", " [") + "]";
        Person bracket = newPerson(parent, bracketName);
        for (List<Person> order : List.of(List.of(literal, bracket), List.of(bracket, literal))) {
            for (Person candidate : order) {
                caseCount++;
                AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve("Ask " + candidate.getName());
                assertTrue(resolution.resources().stream().anyMatch(resource -> resource.id() == candidate.getId()));
                MaskingContext context = new MaskingContext();
                order.forEach(value -> MaskingEngine.maskField(EntityKind.PERSON, value.getName(), context));
                String masked = MaskingEngine.maskFreeText(candidate.getName(), context);
                assertEquals(candidate.getName(), Demasker.demask(masked, context).text());
                String payload = mapper.writeValueAsString(firstProviderRequest(
                        invocation("Ask " + candidate.getName(), context, mapper), mapper).messages());
                assertNoBaseFragments(base, payload, new LinkParityCase(candidate.getName(), candidate.getName()));
                AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
                AiAssistantToolResult scope = scopeResult(candidate,
                        "x".repeat(498) + candidate.getName(), resources, mapper);
                assertTrue(mapper.writeValueAsString(scope.data().get("records")).contains(candidate.getName()));
                assertNoBaseFragments(base, mapper.writeValueAsString(scope.data().get("activities")),
                        new LinkParityCase(candidate.getName(), candidate.getName()));
                AiInvocation capped = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                        new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                                List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                                List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", scope)),
                                context, resources), 256, 0.1);
                String cappedPayload = mapper.writeValueAsString(firstProviderRequest(capped, mapper).messages());
                assertNoBaseFragments(base, cappedPayload, new LinkParityCase(candidate.getName(), candidate.getName()));
                OutboundLeakScan.assertNoLeakStrict(cappedPayload, context, mapper);
            }
        }
        return caseCount;
    }

    private static List<LinkParityCase> linkFragmentCases(String base) {
        List<LinkParityCase> cases = new ArrayList<>();
        cases.add(new LinkParityCase(base, base));
        for (String fragment : List.of("[", "]", "(", ")", "person:1", "(person:1", "](person:1)",
                "(person:", "](person:", "[[(", "]](person:1))", "［", "］", "（", "）")) {
            for (String decorated : List.of(fragment + " " + base, base + " " + fragment)) {
                cases.add(new LinkParityCase(base, decorated));
                cases.add(new LinkParityCase(decorated, decorated));
            }
        }
        String bracketed = base.replace(" ", " [") + "]";
        String completed = bracketed + "(person:1)";
        cases.add(new LinkParityCase(bracketed + "(person:", completed));
        cases.add(new LinkParityCase(bracketed + "(person:1", completed));
        cases.add(new LinkParityCase(bracketed, completed));
        cases.add(new LinkParityCase(bracketed, base));
        for (String linked : List.of(completed, "[" + base + "](record:r9)",
                "[" + completed + "](record:r2)", base.replace(" ", " ［") + "］（person:1）")) {
            cases.add(new LinkParityCase(base, linked));
            cases.add(new LinkParityCase(linked, base));
            cases.add(new LinkParityCase(linked, linked));
        }
        int surname = base.indexOf(' ') + 1;
        for (int depth = 15; depth <= 19; depth++) {
            String linked = base.substring(0, surname) + "[".repeat(depth)
                    + base.substring(surname, surname + 2) + "](person:1)".repeat(depth)
                    + base.substring(surname + 2);
            cases.add(new LinkParityCase(base, linked, depth <= 16));
        }
        return cases;
    }

    private static void assertNoBaseFragments(String base, String payload, LinkParityCase sample) {
        String folded = payload.toLowerCase(Locale.ROOT);
        for (int offset = 0; offset <= base.length() - 4; offset++) {
            String fragment = base.substring(offset, offset + 4);
            assertFalse(folded.contains(fragment), sample + " retained " + fragment);
        }
    }

    private record LinkParityCase(String stored, String text, boolean converged) {
        private LinkParityCase(String stored, String text) {
            this(stored, text, true);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 17})
    void nestedSurnameAtThePassLimitIsMaskedOrRefusedBeforeLookupCapAndProvider(int depth) throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), "Johnathan Smith");
        String spelling = "Johnathan " + "[".repeat(depth) + "Sm" + "](person:1)".repeat(depth) + "ith";
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantIdentifierResolver resolver = resolver();
        if (depth > 16) {
            assertUnsafeNestedTextRefused(person, spelling, mapper, resolver);
            return;
        }
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(spelling);
        assertEquals(List.of(person.getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
        MaskingContext turnContext = new MaskingContext();
        resolver.seed(resolution, turnContext);
        String turnPayload = mapper.writeValueAsString(firstProviderRequest(
                invocation(spelling, turnContext, mapper), mapper).messages());
        assertTrue(turnPayload.contains("{{P1}}"));
        assertFalse(turnPayload.contains("John"));
        assertFalse(turnPayload.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(turnPayload, turnContext, mapper);

        MaskingContext scopeContext = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(scopeContext);
        AiAssistantToolResult scope = scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        assertTrue(mapper.writeValueAsString(scope.data().get("activities")).contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, scopeContext,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", scope)),
                        scopeContext, resources), 256, 0.1);
        String scopePayload = mapper.writeValueAsString(firstProviderRequest(invocation, mapper).messages());
        assertFalse(scopePayload.contains("John"));
        assertFalse(scopePayload.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(scopePayload, scopeContext, mapper);
    }

    private void assertUnsafeNestedTextRefused(Person person, String spelling, ObjectMapper mapper,
            AiAssistantIdentifierResolver resolver) {
        if (providerHarness.isEmpty()) {
            providerHarness = Optional.of(newProviderHarness(mapper));
        }
        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve(spelling));
        assertEquals("malformed_output", exception.terminalReason());
        assertEquals("identifier_text_unsafe", exception.detailReason());
        assertThrows(MaskingLeakException.class, () -> providerHarness.orElseThrow().service().complete(
                invocation(spelling, new MaskingContext(), mapper)));
        assertThrows(MaskingLeakException.class, () -> scopeResult(person, "x".repeat(498) + spelling,
                new AiChatResourceRegistry(new MaskingContext()), mapper));
        verify(providerHarness.orElseThrow().provider(), never()).complete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "John [O'Connor](person:1)",
            "John [O'Connor](record:r1)",
            "[John](company:1) O'Connor",
            "[John O'](deal:1)Connor",
            "John O'[Connor](record:r999)",
            "[John O'Connor](person:999)",
            "[John](person:1) [O'Connor](record:r2)",
            "John [[O'Connor](person:1)](record:r2)",
            "John \uFF3BO'Connor\uFF3D\uFF08person:1\uFF09",
            "John [O'Con\u200Bnor](person:1)",
            "John [O'{{}}Connor](person:1)"
    })
    void linkedNamesWithoutPageContextAreTokenizedInTheFirstProviderRequest(String spelling) throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), "John O'Connor");
        String turn = "What is happening with " + spelling + "?";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(List.of(person.getName()), resolution.identifiers().stream()
                .map(AiAssistantIdentifierResolver.Identifier::value).toList());
        assertEquals(person.getId(), resolution.resources().getFirst().id());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("What is happening with {{P1}}?"));
        assertFalse(input.contains("John"));
        assertFalse(input.contains("Connor"));
        assertFalse(input.contains("person:"));
        assertFalse(input.contains("record:"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"John O'Connor", "Johnathan [Smith]"})
    void foreignLinkTargetsNeverAuthorizeRecordsAndAccessibleLabelsStillTokenize(String name) throws Exception {
        Person local = newPerson(newCompany("Local Parent"), name);
        Organization foreignOrg = new Organization();
        foreignOrg.setName("Foreign " + unique());
        foreignOrg.setSlug("foreign-" + unique());
        organizationMapper.insert(foreignOrg);
        Workspace foreignWorkspace = new Workspace();
        foreignWorkspace.setName("Foreign " + unique());
        foreignWorkspace.setSlug("foreign-" + unique());
        foreignWorkspace.setOrgId(foreignOrg.getId());
        workspaceMapper.insert(foreignWorkspace);
        Company foreignCompany = new Company();
        foreignCompany.setName("Foreign Parent");
        foreignCompany.setWorkspaceId(foreignWorkspace.getId());
        companyMapper.insert(foreignCompany);
        Person foreign = new Person();
        foreign.setName("Foreign Visible Label");
        foreign.setWorkspaceId(foreignWorkspace.getId());
        foreign.setCompany(foreignCompany);
        personMapper.insert(foreign);
        String spelling = name.contains("[") ? name : "[" + name + "]";
        String turn = "Ask " + spelling + "(person:" + foreign.getId() + ") today.";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(List.of(local.getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
        assertTrue(resolver.resolve("Ask [Unmentioned](person:" + foreign.getId() + ").").resources().isEmpty());
        assertTrue(resolver.resolve("Ask [Foreign Visible Label](person:" + local.getId() + ").")
                .resources().isEmpty());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);

        assertTrue(mapper.writeValueAsString(request.messages()).contains("Ask {{P1}} today."));
        assertEquals(Set.of(name), context.identifierDictionary());
    }

    @ParameterizedTest
    @ValueSource(strings = {"John [O'Connor](person:1)", "[John](record:r9) O'Connor"})
    void linkBearingStoredNamesUseTheSameLookupAndRegistrationForm(String name) throws Exception {
        newPerson(newCompany("Fixture Parent"), name);
        AiAssistantIdentifierResolver resolver = resolver();
        String turn = "Ask John O'Connor today.";
        MaskingContext context = new MaskingContext();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(1, resolution.identifiers().size());
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);

        assertTrue(mapper.writeValueAsString(request.messages()).contains("Ask {{P1}} today."));
    }

    @Test
    void pathologicalStoredLinkNestingDoesNotBlockUnrelatedTurns() throws Exception {
        newPerson(newCompany("Fixture Parent"), "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17));

        AiAssistantIdentifierResolver.Resolution resolution = resolver().resolve("An unrelated turn");

        assertTrue(resolution.resources().isEmpty());
        assertTrue(resolution.identifiers().isEmpty());
        ObjectMapper mapper = JsonMapper.builder().build();
        AiCompletionRequest request = firstProviderRequest(invocation("An unrelated turn", new MaskingContext(), mapper), mapper);
        assertTrue(mapper.writeValueAsString(request.messages()).contains("An unrelated turn"));
    }

    @Test
    void seededPathologicalStoredNameIsRedactedInScopeResultsWithoutBlockingTheProvider() throws Exception {
        String raw = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
        Person person = newPerson(newCompany("Fixture Parent"), raw);
        ObjectMapper mapper = JsonMapper.builder().build();
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);

        AiAssistantToolResult scope = scopeResult(person, "Routine update", resources, mapper);

        assertEquals(Set.of(raw), context.identifierDictionary());
        assertEquals(1, scope.data().get("returnedActivities"));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", scope)),
                        context, resources), 256, 0.1);
        String payload = mapper.writeValueAsString(firstProviderRequest(invocation, mapper).messages());
        assertTrue(payload.contains("[redacted]"));
        assertTrue(payload.contains("Routine update"));
        assertFalse(payload.contains("John"));
        assertFalse(payload.contains("Connor"));
        OutboundLeakScan.assertNoLeakStrict(payload, context, mapper);

        String literalMention = "[Visible](person:" + raw + ")";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(literalMention);
        assertEquals(List.of(person.getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
        resolver.seed(resolution, context);
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict(literalMention, context, mapper));
        String mentionPayload = mapper.writeValueAsString(firstProviderRequest(
                invocation(literalMention, context, mapper), mapper).messages());
        assertFalse(mentionPayload.contains("John"));
        assertFalse(mentionPayload.contains("Connor"));
        OutboundLeakScan.assertNoLeakStrict(mentionPayload, context, mapper);

        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class, () -> resolver.resolve(raw));
        assertEquals("identifier_text_unsafe", exception.detailReason());
        assertThrows(MaskingLeakException.class, () -> invocation(raw, context, mapper));
        assertThrows(MaskingLeakException.class,
                () -> scopeResult(person, "x".repeat(498) + raw, resources, mapper));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Johnathan [Smith]|Johnathan [Smith](person:1)",
            "Johnathan [Smith]|Johnathan [Smith](record:r9)",
            "[Johnathan] [Smith]|[Johnathan] [Smith](person:1)",
            "[Johnathan] [Smith]|[Johnathan](person:1) [Smith]",
            "Johnathan Smith]|[Johnathan Smith](person:1)"
    })
    void bracketBearingNamesAreAdmittedAndMaskedInTheFirstProviderRequest(String name, String spelling)
            throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), name);
        AiAssistantIdentifierResolver resolver = resolver();
        String turn = "Ask " + spelling + " today.";
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(List.of(person.getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("Ask {{P1}} today."));
        assertFalse(input.contains("Johnathan"));
        assertFalse(input.contains("Smith"));
        assertFalse(input.contains("person:"));
        assertFalse(input.contains("record:"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Johnathan [Smith](person:1)", "Johnathan [Smith](record:r9)"})
    void bracketBearingNamesAreScreenedBeforeTheProductionCapAndFirstProviderRequest(String spelling)
            throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), "Johnathan [Smith]");
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantToolResult result = scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", result)),
                        context, resources), 256, 0.1);

        AiCompletionRequest request = firstProviderRequest(invocation, mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("[redacted]"));
        assertFalse(input.contains("John"));
        assertFalse(input.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @Test
    void punctuationNamesFromTheRealMapperAreTokenizedInTheFirstProviderRequest() throws Exception {
        Company company = newCompany("Acme, Inc.");
        Person apostrophe = newPerson(company, "John O'Connor");
        Person hyphen = newPerson(company, "Anne-Marie Smith");
        Deal deal = newDeal(company, "J. R. Renewal");
        String text = "What is happening with John O'Connor and Anne-Marie Smith"
                + " at Acme, Inc. on J. R. Renewal?";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(text);
        assertEquals(4, resolution.identifiers().size());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(text, context, mapper), mapper);
        String providerInput = request.systemPrompt() + mapper.writeValueAsString(request.messages());

        for (String name : List.of(
                company.getName(), apostrophe.getName(), hyphen.getName(), deal.getName())) {
            assertFalse(providerInput.contains(name), name);
        }
        assertTrue(providerInput.contains("{{P1}}"));
        assertTrue(providerInput.contains("{{P2}}"));
        assertTrue(providerInput.contains("{{C1}}"));
        assertTrue(providerInput.contains("{{D1}}"));
    }

    @Test
    void namesWrittenAcrossWhitespaceBreaksAreTokenizedInTheFirstProviderRequest() throws Exception {
        Company company = newCompany("Acme, Inc.");
        Person person = newPerson(company, "John O'Connor");
        String text = "What is happening with John\nO'Connor at Acme,  Inc. this week?";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(text);
        assertEquals(2, resolution.identifiers().size());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(text, context, mapper), mapper);
        String providerInput = request.systemPrompt() + mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains(person.getName()));
        assertFalse(providerInput.contains(company.getName()));
        assertFalse(providerInput.contains("O'Connor"));
        assertFalse(providerInput.contains("Acme"));
        assertTrue(providerInput.contains("{{P1}}"));
        assertTrue(providerInput.contains("{{C1}}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Ipek Smith|\u0130pek Smith",
            "IRMA Smith|\u0131rma smith",
            "\u0130pek Smith|Ipek Smith",
            "\u0131rma smith|IRMA Smith",
            "\u0130pek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|\u0130pek Smith",
            "Ipek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|Ipek Smith"
    })
    void simpleUnicodeCaseMentionsWithoutPageContextAreTokenizedInTheFirstProviderRequest(
            String name, String spelling) throws Exception {
        Person person = newPerson(newCompany("Example Company"), name);
        String text = "Ask " + spelling + " today.";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(text);
        assertEquals(1, resolution.identifiers().size());
        assertEquals(person.getName(), resolution.identifiers().getFirst().value());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(text, context, mapper), mapper);
        String providerInput = request.systemPrompt() + mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains(name));
        assertFalse(providerInput.contains(spelling));
        assertTrue(providerInput.contains("Ask {{P1}} today."));
        OutboundLeakScan.assertNoLeakStrict(providerInput, context, mapper);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Ipek Smith|\u0130pek Smith",
            "IRMA Smith|\u0131rma smith",
            "\u0130pek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|\u0130pek Smith"
    })
    void simpleUnicodeCaseVariantsAreScreenedBeforeTheProductionCapAndFirstProviderRequest(
            String name, String spelling) throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), name);
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        ObjectMapper mapper = JsonMapper.builder().build();
        String note = "x".repeat(513 - spelling.length()) + spelling;
        AiAssistantToolResult result = scopeResult(person, note, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", result)),
                        context, resources), 256, 0.1);

        AiCompletionRequest request = firstProviderRequest(invocation, mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("[redacted]"));
        assertFalse(input.contains(spelling.substring(0, 4)));
        assertFalse(input.contains("Smith"));
        assertFalse(input.contains("smith"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8085551", "80855512"})
    void emailPhoneSuffixesCannotReachTheFirstProviderRequest(String suffix) throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        AiCompletionRequest request = firstProviderRequest(
                invocation("Contact alice@example.com" + suffix, new MaskingContext(), mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("alice@example.com"));
        assertFalse(providerInput.contains(suffix));
        assertTrue(providerInput.contains("Contact [redacted]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{}}", "\uFF5B\uFF5B\uFF5D\uFF5D"})
    void delimiterMentionsWithoutPageContextAreTokenizedInTheFirstProviderRequest(String delimiters)
            throws Exception {
        Person person = newPerson(newCompany("Example Company"), "John O'Connor");
        String text = "What is happening with John O'" + delimiters + "Connor?";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(text);
        assertEquals(List.of(person.getName()), resolution.identifiers().stream()
                .map(AiAssistantIdentifierResolver.Identifier::value).toList());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(text, context, mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("John"));
        assertFalse(providerInput.contains("Connor"));
        assertTrue(providerInput.contains("What is happening with {{P1}}?"));
    }

    @Test
    void explicitlySeededFullwidthDelimiterIdentifierCannotReachTheFirstProviderRequest() throws Exception {
        String name = "John\uFF5B\uFF5B\uFF5D\uFF5Dathan Smith";
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, name, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation("Ask " + name + " today.", context, mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("Johnathan"));
        assertTrue(providerInput.contains("Ask {{P1}} today."));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "John{{athan Smith|John{}}{athan Smith",
            "John{}}{athan Smith|Johnathan Smith"
    })
    void repeatedlySanitizedDelimitersCannotBypassLookupAndTheFirstProviderRequest(String name, String spelling)
            throws Exception {
        newPerson(newCompany("Example Company"), name);
        String text = "Ask " + spelling + " today.";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(text);
        assertEquals(1, resolution.identifiers().size());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(text, context, mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("John"));
        assertFalse(providerInput.contains("athan"));
        assertTrue(providerInput.contains("Ask {{P1}} today."));
    }

    /**
     * Generates adversarial names in twelve fresh workspaces across all three record kinds.
     * Raw, canonical, linked and both dotted-I spellings go through production preparation before SQL:
     * the mapper's superset contract requires that canonical operand, just as the resolver supplies it.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
    void sqlCandidatesAreASupersetOfJavaMatchesForGeneratedAdversarialNames(int batch) {
        List<String> bases = List.of("John O'Connor", "John Smith", "Anne-Marie Smith", "Acme, Inc.",
                "Ipek Smith", "\u0130pek Smith", "i\u0307pek Smith", "\u212Aelvin Smith",
                "Cafe\u0301 Smith", "\u337F Team", "\uFB03 Team", "\u039F\u03A3 Team");
        List<String> braces = List.of("{", "}", "{{", "}}", "{}}{", "}{", "\uFF5B", "\uFF5D",
                "\uFE5B", "\uFE5C", "\uFE37", "\uFE38");
        List<String> spaces = List.of(" ", "  ", "\t", "\n", "\u00A0", "\u3000", " \u00A0\u3000 ", "\u0085");
        Company parent = newCompany("Fixture Parent");
        List<ParityName> names = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            String base = bases.get((batch + index) % bases.size());
            String brace = braces.get(batch);
            String spaced = base.replace(" ", spaces.get(index % spaces.size()));
            String name = switch (index % 4) {
                case 0 -> brace + spaced;
                case 1 -> spaced + brace;
                case 2 -> spaced.substring(0, 2) + brace + spaced.substring(2);
                default -> spaced.replaceFirst(" ", " " + brace + " ");
            };
            name += " Ref" + batch + "x" + index;
            switch (index % 3) {
                case 0 -> names.add(new ParityName("person", newPerson(parent, name).getId(), name));
                case 1 -> names.add(new ParityName("company", newCompany(name).getId(), name));
                default -> names.add(new ParityName("deal", newDeal(parent, name).getId(), name));
            }
        }
        names.add(new ParityName("person", newPerson(parent, "\u0301".repeat(4)).getId(), "\u0301".repeat(4)));
        String invisible = "Zero\u200BWidth\u00AD Holdings";
        names.add(new ParityName("company", newCompany(invisible).getId(), invisible));
        String combining = "\u0130\u0327 Holdings";
        names.add(new ParityName("company", newCompany(combining).getId(), combining));
        for (String bracketName : List.of("Johnathan [Smith]", "[Johnathan] [Smith]", "Johnathan Smith]")) {
            names.add(new ParityName("person", newPerson(parent, bracketName).getId(), bracketName));
        }
        for (ParityName target : names) {
            String canonical = expectedCanonical(target.value());
            List<String> spellings = new ArrayList<>(List.of(target.value(), canonical));
            if (target.value().contains("]")) {
                spellings.add(target.value().replace("]", "](person:999)"));
                spellings.add("[" + target.value() + "](record:r1)");
                spellings.add(canonical.replace("[", "").replace("]", ""));
            }
            int split = canonical.offsetByCodePoints(0, canonical.codePointCount(0, canonical.length()) / 2);
            spellings.add(canonical.substring(0, split) + "[" + canonical.substring(split) + "](person:999)");
            spellings.add("[" + canonical.substring(0, split) + "](record:r1)" + canonical.substring(split));
            if (canonical.contains("ipek")) {
                spellings.add(canonical.replace("ipek", "\u0130pek"));
                spellings.add(canonical.replace("ipek", "i\u0307pek"));
            }
            for (String spelling : spellings) {
                String turn = "Ask " + spelling + " today.";
                MaskingEngine.MentionScanText scan = MaskingEngine.mentionScanText(turn);
                assertEquals(expectedCanonical(turn), scan.normalizedText());
                Set<String> javaMatches = names.stream()
                        .filter(name -> MaskingEngine.containsIdentifierMention(scan, name.value()))
                        .map(ParityName::key).collect(Collectors.toSet());
                assertTrue(javaMatches.contains(target.key()), target.value());
                Set<String> candidates = identifierMapper.findMentionedRecords(workspace.getId(),
                        "[" + workspace.getId() + "]", scan.lookupText(), 200, "", 0).stream()
                        .map(name -> name.getKind() + ":" + name.getId()).collect(Collectors.toSet());
                assertTrue(candidates.containsAll(javaMatches),
                        () -> "SQL dropped Java matches for batch " + batch + " and " + target.value());
            }
        }
    }

    /**
     * Every visible record named outside printable ASCII is a candidate for every turn, because
     * MySQL cannot reproduce the Java canonical form for it. A workspace of such records — the
     * ordinary Japanese-market shape — must still resolve its one genuine mention instead of
     * failing the turn closed on candidate volume, for an ASCII and a Japanese turn alike.
     */
    @Test
    void aWorkspaceOfNonAsciiNamedRecordsStillResolvesItsOneGenuineMention() {
        Company parent = newCompany("\u65E5\u672C\u5546\u4E8B");
        for (int index = 0; index < 250; index++) {
            newPerson(parent, "\u4F50\u85E4\u592A\u90CE%03d".formatted(index) + "東京営業所".repeat(4));
        }
        newPerson(parent, "Acme Renewal KK");
        AiAssistantIdentifierResolver resolver = resolver();

        for (String turn : List.of("Ask Acme Renewal KK today.",
                "\u4ECA\u65E5\u306EAcme Renewal KK\u306E\u4E88\u5B9A\u306F\uFF1F")) {
            AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);

            assertEquals(1, resolution.identifiers().size(), turn);
            assertEquals("Acme Renewal KK", resolution.identifiers().getFirst().value(), turn);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cafe{{}}\u0301 Smith", "Cafe{}\u0301 Smith", "Cafe{\u0301 Smith",
            "Cafe\u200B\u0301 Smith"})
    void composedNamesAreTokenizedAndSentAfterDelimiterAndIgnorableRemoval(String spelling) throws Exception {
        newPerson(newCompany("Fixture Parent"), "Caf\u00e9 Smith");
        String turn = "Meeting with " + spelling + " tomorrow";
        AiAssistantIdentifierResolver resolver = resolver();
        MaskingContext context = new MaskingContext();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(1, resolution.identifiers().size());
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("Meeting with {{P1}} tomorrow"));
        assertFalse(input.contains("Cafe"));
        assertFalse(input.contains("Caf\u00e9"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @Test
    void jsonAndCodeBracesReachTheProviderWithThePersonTokenized() throws Exception {
        newPerson(newCompany("Fixture Parent"), "Kenji Sato");
        String turn = "Config from Kenji Sato: {\"retries\": 3} and code{}";
        AiAssistantIdentifierResolver resolver = resolver();
        MaskingContext context = new MaskingContext();
        resolver.seed(resolver.resolve(turn), context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);
        String expected = mapper.writeValueAsString("Config from {{P1}}: {\"retries\": 3} and code{}");
        assertTrue(request.messages().stream().anyMatch(message ->
                message.content().contains(expected)));
        OutboundLeakScan.assertNoLeakStrict(mapper.writeValueAsString(request.messages()), context, mapper);
    }

    @Test
    void invisibleTurnCharactersCannotBypassRealMapperAdmissionOrProviderMasking() throws Exception {
        newPerson(newCompany("Fixture Parent"), "Johnathan Smith");
        String turn = "Met Johnathan\u200B Smith today.";
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(1, resolution.identifiers().size());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);

        assertTrue(mapper.writeValueAsString(request.messages()).contains("Met {{P1}} today."));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "John {}}{ O'Connor|What is happening with John O'Connor?",
            "John {}}{ Smith|Ask John Smith today.",
            "{Johnathan Smith|Ask Johnathan Smith today."
    })
    void storedBraceVariantsWithoutPageContextAreTokenizedInTheFirstProviderRequest(String name, String turn)
            throws Exception {
        newPerson(newCompany("Fixture Parent"), name);
        AiAssistantIdentifierResolver resolver = resolver();
        AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
        assertEquals(1, resolution.identifiers().size());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation(turn, context, mapper), mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("{{P1}}"));
        assertFalse(input.contains("John"));
        assertFalse(input.contains("Smith"));
        assertFalse(input.contains("Connor"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Johnathan Smith}|Johnathan Smith}}",
            "{Johnathan Smith|{{Johnathan Smith"
    })
    void storedBraceVariantsAreScreenedBeforeTheScopeCapAndFirstProviderRequest(String name, String spelling)
            throws Exception {
        Person person = newPerson(newCompany("Fixture Parent"), name);
        AiAssistantIdentifierResolver resolver = resolver();
        MaskingContext context = new MaskingContext();
        resolver.seed(resolver.resolve("Ask " + name + " today."), context);
        assertTrue(context.identifierDictionary().contains(name));
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantToolResult result = scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("[redacted]"));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(new AiAssistantPromptAssembler.ToolTurn(1, "scope_activities", result)),
                        context, resources), 256, 0.1);

        AiCompletionRequest request = firstProviderRequest(invocation, mapper);
        String input = mapper.writeValueAsString(request.messages());

        for (String word : List.of("Johnathan", "Smith")) {
            for (int start = 0; start <= word.length() - 4; start++) {
                String fragment = word.substring(start, start + 4).toLowerCase(Locale.ROOT);
                assertFalse(scope.toLowerCase(Locale.ROOT).contains(fragment), fragment);
                assertFalse(input.toLowerCase(Locale.ROOT).contains(fragment), fragment);
            }
        }
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @Test
    void aCompanyNamedUserContentReachesTheProviderWithoutInventedEnvelopeIdentifiers() throws Exception {
        newCompany("User Content");
        AiAssistantIdentifierResolver resolver = resolver();
        MaskingContext context = new MaskingContext();
        resolver.seed(resolver.resolve("Ask User Content today."), context);
        assertTrue(context.identifierDictionary().contains("User Content"));
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = firstProviderRequest(invocation("Ask User Content today.", context, mapper), mapper);

        assertTrue(mapper.writeValueAsString(request.messages()).contains("Ask {{C1}} today."));
    }

    @Test
    void outboundScanKeepsDecodedFieldsSeparateAndStillRejectsSingleFieldIdentifiers() {
        newCompany("User Content");
        AiAssistantIdentifierResolver resolver = resolver();
        MaskingContext context = new MaskingContext();
        resolver.seed(resolver.resolve("Ask User Content today."), context);
        ObjectMapper mapper = JsonMapper.builder().build();

        for (String payload : List.of("{\"role\":\"user\",\"content\":\"{{C1}}\"}",
                "[\"User\",\"Content\"]", "{\"User\":\"Content\"}")) {
            assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(payload, context, mapper));
        }
        for (String payload : List.of("{\"content\":\"User Content\"}",
                "{\"User {}}{ Content\":\"safe\"}", "{\"content\":\"User\\u0085Content\"}",
                "{\"x\":\"User Content\",\"x\":\"safe\"}",
                "{\"x\":\"User\\u0085Content\",\"x\":\"safe\"}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, mapper));
        }
    }

    private AiAssistantToolResult scopeResult(
            Person person, String note, AiChatResourceRegistry resources, ObjectMapper mapper) {
        if (scopeHarness.isEmpty()) {
            scopeHarness = Optional.of(newScopeHarness(mapper));
        }
        ScopeHarness harness = scopeHarness.orElseThrow();
        reset(harness.people(), harness.activities());
        when(harness.people().getAssistantProcessablePersonIds(workspace.getId())).thenReturn(List.of(person.getId()));
        when(harness.people().getByIds(eq(workspace.getId()), anyList())).thenReturn(List.of(person));
        when(harness.activities().countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(), anyBoolean())).thenReturn(1L);
        when(harness.activities().getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt())).thenReturn(List.of(
                        new AiAssistantScopeActivity(1, person.getId(), "meeting", "Follow up", note,
                                "2026-08-22 10:00:00", person.getId(), null)));
        try {
            return harness.service().scopeActivities(
                    AiChatQueryScope.none(), "person", null, List.of(), 30, 50, 5, resources);
        } finally {
            clearInvocations(harness.mocks().toArray());
        }
    }

    private ScopeHarness newScopeHarness(ObjectMapper mapper) {
        int firstMock = fixtureMocks.size();
        ActivityMapper activities = fixtureMock(ActivityMapper.class);
        PersonMapper people = fixtureMock(PersonMapper.class);
        WorkspaceService workspaceService = fixtureMock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess scope = fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(scope.getForWorkspace(workspace.getId())).thenReturn(new WorkspaceScope(
                workspace.getOrgId(), List.of(workspace.getId()), "[" + workspace.getId() + "]"));
        AiAssistantScopeReadService service = new AiAssistantScopeReadService(
                activities, fixtureMock(SegmentService.class), fixtureMock(SegmentMapper.class), fixtureMock(SavedViewService.class),
                fixtureMock(ScoringService.class), fixtureMock(DealRiskService.class), people, companyMapper, dealMapper,
                workspaceService, scope, mapper, Clock.systemUTC());
        return new ScopeHarness(service, people, activities,
                List.copyOf(fixtureMocks.subList(firstMock, fixtureMocks.size())));
    }

    private record ScopeHarness(AiAssistantScopeReadService service, PersonMapper people,
            ActivityMapper activities, List<Object> mocks) {
    }

    /**
     * Independent restatement of the production canonical form: normalize, drop the invisible
     * default-ignorable code points, fold separators, cancel the doubled delimiters to a fixed
     * point, normalize again so a cancellation cannot leave a combining mark detached, then delete
     * every remaining brace, normalize the exposed compositions, collapse, trim and fold case
     * with one uppercase-then-lowercase mapping per code point, delete dots above immediately after
     * folded i, then normalize exposed compositions.
     */
    private static String expectedCanonical(String raw) {
        String prepared = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\x{00AD}\\x{034F}\\x{061C}\\x{115F}\\x{1160}\\x{17B4}\\x{17B5}"
                        + "\\x{180B}-\\x{180F}\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}\\x{2060}-\\x{206F}"
                        + "\\x{3164}\\x{FE00}-\\x{FE0F}\\x{FEFF}\\x{FFA0}\\x{FFF0}-\\x{FFF8}"
                        + "\\x{1BCA0}-\\x{1BCA3}\\x{1D173}-\\x{1D17A}\\x{E0000}-\\x{E0FFF}]", "")
                .replaceAll("[\\p{Z}\\p{Cc}\\s]", " ");
        while (prepared.contains("{{") || prepared.contains("}}")) {
            prepared = prepared.replace("{{", "").replace("}}", "");
        }
        prepared = prepared.replaceAll("\\[([^\\]\\n]*)\\]\\((?:person|company|deal|record):[^)\\n]*\\)", "$1");
        String composed = Normalizer.normalize(prepared.replace("{", "").replace("}", "").replace("[", "").replace("]", ""), Normalizer.Form.NFKC)
                .replaceAll(" +", " ").trim();
        StringBuilder folded = new StringBuilder(composed.length());
        composed.codePoints().map(Character::toUpperCase).map(Character::toLowerCase)
                .forEach(folded::appendCodePoint);
        return Normalizer.normalize(folded.toString().replaceAll("i\u0307+", "i"), Normalizer.Form.NFKC);
    }

    private record ParityName(String kind, int id, String value) {
        String key() {
            return kind + ":" + id;
        }
    }

    private AiInvocation invocation(String text, MaskingContext context, ObjectMapper mapper) {
        AiChatMessage message = new AiChatMessage();
        message.setAuthorKind("user");
        message.setContent(text);
        return new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(message), new AiAssistantToolResult(Map.of(), List.of()), List.of(),
                        context, new AiChatResourceRegistry()), 256, 0.1);
    }

    private AiAssistantIdentifierResolver resolver() {
        WorkspaceService workspaceService = fixtureMock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess scope =
                fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(scope.getForWorkspace(workspace.getId())).thenReturn(new WorkspaceScope(
                workspace.getOrgId(), List.of(workspace.getId()), "[" + workspace.getId() + "]"));
        return new AiAssistantIdentifierResolver(identifierMapper, workspaceService, scope);
    }

    private AiCompletionRequest firstProviderRequest(AiInvocation invocation, ObjectMapper mapper) {
        if (providerHarness.isEmpty()) {
            providerHarness = Optional.of(newProviderHarness(mapper));
        }
        ProviderHarness harness = providerHarness.orElseThrow();
        try {
            harness.service().complete(invocation);
            ArgumentCaptor<AiCompletionRequest> request = ArgumentCaptor.forClass(AiCompletionRequest.class);
            verify(harness.provider()).complete(request.capture());
            return request.getValue();
        } finally {
            clearInvocations(harness.mocks().toArray());
        }
    }

    private ProviderHarness newProviderHarness(ObjectMapper mapper) {
        int firstMock = fixtureMocks.size();
        AiProvider provider = fixtureMock(AiProvider.class);
        when(provider.providerId()).thenReturn("deterministic");
        when(provider.maxOutputTokens(any())).thenReturn(4_096);
        when(provider.contextWindowTokens(any())).thenReturn(128_000);
        when(provider.complete(any())).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            String output = request.providerAttemptExecutor().execute(() -> "Ready");
            return new AiCompletionResult(output, 1, 1, "stop");
        });
        WorkspaceService workspaceService = fixtureMock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(workspaceService.getCurrentOrgId()).thenReturn(workspace.getOrgId());
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        AiProviderConfigService configService = fixtureMock(AiProviderConfigService.class);
        when(configService.resolveForOrg(workspace.getOrgId(), 11)).thenReturn(new ResolvedAiProvider(
                "deterministic", null, "deterministic-model", "https://provider.example.test/v1",
                null, null, null, false, true, AiCredentials.of(Map.of())));
        AiOrganizationBudgetCoordinator budget = fixtureMock(AiOrganizationBudgetCoordinator.class);
        AiOrganizationBudgetCoordinator.Lease budgetLease = fixtureMock(AiOrganizationBudgetCoordinator.Lease.class);
        when(budgetLease.deadline()).thenAnswer(call -> AiRequestDeadline.afterMillis(60_000));
        when(budget.reserve(eq(workspace.getOrgId()), any(AiInvocation.class), anyString()))
                .thenReturn(budgetLease);
        AiFeatureGate gate = fixtureMock(AiFeatureGate.class);
        when(gate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        AiInvocationService invocationService = new AiInvocationService(
                gate, fixtureMock(AiInvocationAdmissionService.class), fixtureMock(AiMediaAdmissionService.class),
                configService, new AiProviderRouter(List.of(provider)), new AiRestrictionEpoch(),
                workspaceService, fixtureMock(AuditService.class), mapper, budget, Clock.systemUTC());

        return new ProviderHarness(invocationService, provider,
                List.copyOf(fixtureMocks.subList(firstMock, fixtureMocks.size())));
    }

    private record ProviderHarness(AiInvocationService service, AiProvider provider, List<Object> mocks) {
    }

    private Company newCompany(String name) {
        Company company = new Company();
        company.setName(name);
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(Company company, String name) {
        Person person = new Person();
        person.setName(name);
        person.setCompany(company);
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }

    private Deal newDeal(Company company, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(workspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(1);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        Deal deal = new Deal();
        deal.setName(name);
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

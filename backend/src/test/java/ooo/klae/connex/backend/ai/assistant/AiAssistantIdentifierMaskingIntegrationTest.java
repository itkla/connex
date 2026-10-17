package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
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
    @Autowired private jakarta.validation.Validator validator;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private ooo.klae.connex.backend.mappers.UserMapper userMapper;
    @Autowired private ooo.klae.connex.backend.mappers.NoteMapper noteMapper;
    @Autowired private ooo.klae.connex.backend.services.ReferenceService referenceService;
    @Autowired private ooo.klae.connex.backend.tenant.TenantContext tenantContext;
    @Autowired private AiAssistantToolExecutor realToolExecutor;
    @Autowired private ooo.klae.connex.backend.mappers.ActivityMapper activityMapper;
    @Autowired private ooo.klae.connex.backend.mappers.TaskMapper taskMapper;
    @Autowired private AiAssistantScopeReadService scopeReadService;
    @Autowired private AiAssistantWorkBriefReadService workBriefReadService;
    @Autowired private AiAssistantWriteToolService writeToolService;

    private Workspace workspace;
    private Workspace committedForeignWorkspace;
    private List<String> committedMemberUsernames = List.of();
    private boolean committedMemberFixture;
    private AiAssistantMaskingTestSupport support;

    /** Releases request captures retained by Mockito's inline mock registry after each test. */
    @AfterEach
    void releaseFixtureMocks() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (committedMemberFixture && workspace != null) {
            jdbc.update("DELETE FROM entity_reference WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM note WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM activity WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM task WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id IN (?, ?)", workspace.getId(),
                    committedForeignWorkspace == null ? 0 : committedForeignWorkspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id IN (?, ?)", workspace.getId(),
                    committedForeignWorkspace == null ? 0 : committedForeignWorkspace.getId());
            if (!committedMemberUsernames.isEmpty()) {
                jdbc.update("DELETE FROM app_user WHERE username IN (?, ?, ?, ?)", committedMemberUsernames.toArray());
            }
            jdbc.update("DELETE FROM organization WHERE id = ?", workspace.getOrgId());
        }
        if (support != null) {
            support.releaseFixtureMocks();
        }
    }

    @BeforeEach
    void setUpWorkspace(org.junit.jupiter.api.TestInfo info) {
        committedMemberFixture = info.getTestMethod().map(method -> Set.of(
                "cp1252ConnectionKeepsAdmittedNameOutOfProviderPayload",
                "realMemberDirectoryAndAuthoredReferenceLabelsStayMaskedAcrossRenameAndFreshReplay",
                "storedTaskOutcomesMaskRenamedAndPendingReferencesOnBothProtocolsAndFreshReplay",
                "parserOnlyStoredLabelsStayMaskedBeforeProviderSerialization",
                "queuedUserReferenceSpellingsRemainMaskedOnFirstDispatchAndFreshReplay")
                .contains(method.getName())).orElse(false);
        Organization organization = new Organization();
        organization.setName("Masking " + unique());
        organization.setSlug("masking-" + unique());
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setName("Masking " + unique());
        workspace.setSlug("masking-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        support = new AiAssistantMaskingTestSupport(workspace.getId(), workspace.getOrgId());
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
            assertTrue(identifierMapper.findMentionedRecords(workspace.getId(), "[" + workspace.getId() + "]", scan.literalText(), scan.labelText(), 200, "", 0).stream().anyMatch(row -> row.getId() == person.getId()
                            && "person".equals(row.getKind())), sample.toString());
            assertTrue(MaskingEngine.containsIdentifierMention(scan, sample.stored()), sample.toString());
            AiAssistantIdentifierResolver.Resolution resolution = resolver.resolve(turn);
            assertTrue(resolution.resources().stream().anyMatch(resource -> resource.id() == person.getId()
                    && "person".equals(resource.kind())), sample.toString());
            MaskingContext turnContext = new MaskingContext();
            resolver.seed(resolution, turnContext);
            String turnPayload = mapper.writeValueAsString(support.firstProviderRequest(
                    support.invocation(turn, turnContext, mapper), mapper).messages());
            assertNoBaseFragments(base, turnPayload, sample);
            OutboundLeakScan.assertNoLeakStrict(turnPayload, turnContext, mapper);

            MaskingContext scopeContext = new MaskingContext();
            AiChatResourceRegistry resources = new AiChatResourceRegistry(scopeContext);
            AiAssistantToolResult scope = support.scopeResult(person, "x".repeat(498) + sample.text(), resources, mapper);
            String activities = mapper.writeValueAsString(scope.data().get("activities"));
            assertNoBaseFragments(base, activities, sample);
            AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, scopeContext,
                    new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                            List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                            List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", scope)),
                            scopeContext, resources,
                            AiAssistantToolCatalog.ALL), 256, 0.1);
            String scopePayload = mapper.writeValueAsString(support.firstProviderRequest(invocation, mapper).messages());
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
                String payload = mapper.writeValueAsString(support.firstProviderRequest(
                        support.invocation("Ask " + candidate.getName(), context, mapper), mapper).messages());
                assertEquals(candidate.getName(), Demasker.demask(masked, context).text());
                assertNoBaseFragments(base, payload, new LinkParityCase(candidate.getName(), candidate.getName()));
                AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
                AiAssistantToolResult scope = support.scopeResult(candidate,
                        "x".repeat(498) + candidate.getName(), resources, mapper);
                assertTrue(mapper.writeValueAsString(scope.data().get("records")).contains(candidate.getName()));
                assertNoBaseFragments(base, mapper.writeValueAsString(scope.data().get("activities")),
                        new LinkParityCase(candidate.getName(), candidate.getName()));
                AiInvocation capped = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                        new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                                List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                                List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", scope)),
                                context, resources,
                                AiAssistantToolCatalog.ALL), 256, 0.1);
                String cappedPayload = mapper.writeValueAsString(support.firstProviderRequest(capped, mapper).messages());
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
        String turnPayload = mapper.writeValueAsString(support.firstProviderRequest(
                support.invocation(spelling, turnContext, mapper), mapper).messages());
        assertTrue(turnPayload.contains("{{P1}}"));
        assertFalse(turnPayload.contains("John"));
        assertFalse(turnPayload.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(turnPayload, turnContext, mapper);

        MaskingContext scopeContext = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(scopeContext);
        AiAssistantToolResult scope = support.scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        assertTrue(mapper.writeValueAsString(scope.data().get("activities")).contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, scopeContext,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", scope)),
                        scopeContext, resources,
                        AiAssistantToolCatalog.ALL), 256, 0.1);
        String scopePayload = mapper.writeValueAsString(support.firstProviderRequest(invocation, mapper).messages());
        assertFalse(scopePayload.contains("John"));
        assertFalse(scopePayload.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(scopePayload, scopeContext, mapper);
    }

    private void assertUnsafeNestedTextRefused(Person person, String spelling, ObjectMapper mapper,
            AiAssistantIdentifierResolver resolver) {
        AiAssistantMaskingTestSupport.ProviderHarness harness = support.providerHarness(mapper);
        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class,
                () -> resolver.resolve(spelling));
        assertEquals("malformed_output", exception.terminalReason());
        assertEquals("identifier_text_unsafe", exception.detailReason());
        assertThrows(MaskingLeakException.class, () -> harness.service().complete(
                support.invocation(spelling, new MaskingContext(), mapper)));
        assertThrows(MaskingLeakException.class, () -> support.scopeResult(person, "x".repeat(498) + spelling,
                new AiChatResourceRegistry(new MaskingContext()), mapper));
        verify(harness.provider(), never()).complete(any());
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);

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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);

        assertTrue(mapper.writeValueAsString(request.messages()).contains("Ask {{P1}} today."));
    }

    @Test
    void pathologicalStoredLinkNestingDoesNotBlockUnrelatedTurns() throws Exception {
        newPerson(newCompany("Fixture Parent"), "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17));

        AiAssistantIdentifierResolver.Resolution resolution = resolver().resolve("An unrelated turn");

        assertTrue(resolution.resources().isEmpty());
        assertTrue(resolution.identifiers().isEmpty());
        ObjectMapper mapper = JsonMapper.builder().build();
        AiCompletionRequest request = support.firstProviderRequest(support.invocation("An unrelated turn", new MaskingContext(), mapper), mapper);
        assertTrue(mapper.writeValueAsString(request.messages()).contains("An unrelated turn"));
    }

    @Test
    void seededPathologicalStoredNameIsRedactedInScopeResultsWithoutBlockingTheProvider() throws Exception {
        String raw = "[".repeat(17) + "John O'Connor" + "](person:1)".repeat(17);
        Person person = newPerson(newCompany("Fixture Parent"), raw);
        ObjectMapper mapper = JsonMapper.builder().build();
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);

        AiAssistantToolResult scope = support.scopeResult(person, "Routine update", resources, mapper);

        assertEquals(Set.of(raw), context.identifierDictionary());
        assertEquals(1, scope.data().get("returnedActivities"));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", scope)),
                        context, resources,
                        AiAssistantToolCatalog.ALL), 256, 0.1);
        String payload = mapper.writeValueAsString(support.firstProviderRequest(invocation, mapper).messages());
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
        String mentionPayload = mapper.writeValueAsString(support.firstProviderRequest(
                support.invocation(literalMention, context, mapper), mapper).messages());
        assertFalse(mentionPayload.contains("John"));
        assertFalse(mentionPayload.contains("Connor"));
        OutboundLeakScan.assertNoLeakStrict(mentionPayload, context, mapper);

        AiAssistantLoopException exception = assertThrows(AiAssistantLoopException.class, () -> resolver.resolve(raw));
        assertEquals("identifier_text_unsafe", exception.detailReason());
        assertThrows(MaskingLeakException.class, () -> support.invocation(raw, context, mapper));
        assertThrows(MaskingLeakException.class,
                () -> support.scopeResult(person, "x".repeat(498) + raw, resources, mapper));
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("Ask {{P1}} today."));
        assertFalse(input.contains("Johnathan"));
        assertFalse(input.contains("Smith"));
        assertFalse(input.contains("person:"));
        assertFalse(input.contains("record:"));
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(text, context, mapper), mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(text, context, mapper), mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(text, context, mapper), mapper);
        String providerInput = request.systemPrompt() + mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains(name));
        assertFalse(providerInput.contains(spelling));
        assertTrue(providerInput.contains("Ask {{P1}} today."));
        OutboundLeakScan.assertNoLeakStrict(providerInput, context, mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(text, context, mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("John"));
        assertFalse(providerInput.contains("Connor"));
        assertTrue(providerInput.contains("What is happening with {{P1}}?"));
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(text, context, mapper), mapper);
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
                Set<String> candidates = identifierMapper.findMentionedRecords(workspace.getId(), "[" + workspace.getId() + "]", scan.literalText(), scan.labelText(), 200, "", 0).stream()
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);

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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation(turn, context, mapper), mapper);
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
        AiAssistantToolResult result = support.scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("[redacted]"));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", result)),
                        context, resources,
                        AiAssistantToolCatalog.ALL), 256, 0.1);

        AiCompletionRequest request = support.firstProviderRequest(invocation, mapper);
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

        AiCompletionRequest request = support.firstProviderRequest(support.invocation("Ask User Content today.", context, mapper), mapper);

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

    @Test
    void malformedUtf16JsonWriterKeepsStoredNameOutOfProviderPayload() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        byte[] json = "{\"name\":\"John \\ud800{\\udc00 Smith\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var dto = mapper.readValue(json, ooo.klae.connex.backend.dto.PersonDto.class);
        assertTrue(validator.validate(dto).isEmpty());
        assertEquals("John \ud800{\udc00 Smith", dto.getName());
        Person person = dto.toBean();
        person.setWorkspaceId(workspace.getId());
        assertEquals(1, personMapper.insert(person));
        var stored = jdbc.queryForMap("SELECT name, ai_identifier_source_name, ai_identifier_literal_key, ai_identifier_label_key, ai_identifier_key_version FROM person WHERE workspace_id = ? AND id = ?",
                workspace.getId(), person.getId());
        assertEquals("John ?{? Smith", stored.get("name"));
        assertEquals(stored.get("name"), stored.get("ai_identifier_source_name"));
        assertEquals(ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys.UNKEYED, stored.get("ai_identifier_literal_key"));
        assertEquals(ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys.UNKEYED, stored.get("ai_identifier_label_key"));
        assertEquals(ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys.VERSION, ((Number) stored.get("ai_identifier_key_version")).intValue());
        String turn = "Ask John ?{? Smith today.";
        AiAssistantIdentifierResolver resolver = resolver();
        var resolution = resolver.resolve(turn);
        assertEquals(List.of(person.getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
        assertEquals(List.of("John ?{? Smith"), resolution.identifiers().stream()
                .map(AiAssistantIdentifierResolver.Identifier::value).toList());
        MaskingContext context = new MaskingContext();
        resolver.seed(resolution, context);
        String payload = mapper.writeValueAsString(support.firstProviderRequest(
                support.invocation(turn, context, mapper), mapper).messages());
        assertTrue(payload.contains("Ask {{P1}} today."));
        assertFalse(payload.contains("John"));
        assertFalse(payload.contains("Smith"));
        assertFalse(payload.contains((String) stored.get("name")));
        OutboundLeakScan.assertNoLeakStrict(payload, context, mapper);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void cp1252ConnectionKeepsAdmittedNameOutOfProviderPayload() throws Exception {
        Person japanese = newPerson(newCompany("Japanese fixture"), "株式会社 Team");
        Person supplementary = newPerson(newCompany("Supplementary fixture"), "𠀀 Team");
        jdbc.update("UPDATE person SET ai_identifier_key_version = 0, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                workspace.getId(), japanese.getId());
        String url = System.getenv("CONNEX_DB_URL")
                .replaceAll("(?i)(characterEncoding|connectionCollation)=[^&]*&?", "");
        url += (url.contains("?") ? "&" : "?") + "characterEncoding=Cp1252&connectionCollation=latin1_bin";
        var dataSource = new org.apache.ibatis.datasource.unpooled.UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"));
        var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "identifier-cp1252", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ooo.klae.connex.backend.tenant.TenantScopeInterceptor(tenantContext, true, false));
        for (String resource : List.of("mappers/PersonMapper.xml", "mappers/AiAssistantIdentifierMapper.xml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertTrue(input != null);
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        try (var session = sessions.openSession(true)) {
            try (var statement = session.getConnection().createStatement();
                    var rows = statement.executeQuery("SELECT @@character_set_client, @@character_set_connection")) {
                assertTrue(rows.next());
                assertEquals("latin1", rows.getString(1));
                assertEquals("latin1", rows.getString(2));
            }
            Person person = new Person();
            person.setWorkspaceId(workspace.getId());
            person.setName("René Smith");
            session.getMapper(PersonMapper.class).insert(person);
            Person micro = new Person();
            micro.setWorkspaceId(workspace.getId());
            micro.setName("µ Team");
            session.getMapper(PersonMapper.class).insert(micro);
            Person unicodeWriter = new Person();
            unicodeWriter.setWorkspaceId(workspace.getId());
            unicodeWriter.setName("Ｃｐ Writer 𠀀");
            session.getMapper(PersonMapper.class).insert(unicodeWriter);
            var cpIdentifiers = session.getMapper(AiAssistantIdentifierMapper.class);
            assertEquals(1, cpIdentifiers.updatePersonKeysIfNameCurrent(workspace.getId(), japanese.getId(),
                    japanese.getName(), ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys.VERSION));
            assertEquals(0, cpIdentifiers.countPendingKeys(workspace.getId(),
                    ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys.VERSION));
            for (Person saved : List.of(japanese, unicodeWriter)) {
                assertEquals(saved.getName(), jdbc.queryForObject("SELECT ai_identifier_source_name FROM person WHERE workspace_id = ? AND id = ?",
                        String.class, workspace.getId(), saved.getId()));
                assertEquals(saved.getName(), jdbc.queryForObject("SELECT name FROM person WHERE workspace_id = ? AND id = ?",
                        String.class, workspace.getId(), saved.getId()));
            }
            assertEquals("μ team", jdbc.queryForObject("SELECT ai_identifier_literal_key FROM person WHERE workspace_id = ? AND id = ?",
                    String.class, workspace.getId(), micro.getId()));
            WorkspaceService workspaceService = support.fixtureMock(WorkspaceService.class);
            OrganizationWorkspaceScopeControlAccess scope = support.fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
            when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
            when(scope.getForWorkspace(workspace.getId())).thenReturn(new WorkspaceScope(
                    workspace.getOrgId(), List.of(workspace.getId()), "[" + workspace.getId() + "]"));
            var resolver = new AiAssistantIdentifierResolver(session.getMapper(AiAssistantIdentifierMapper.class),
                    workspaceService, scope, referenceService);
            for (var sample : List.of(Map.entry(person, "Ask René Smith today."),
                    Map.entry(japanese, "Ask ㍿ Team today."), Map.entry(supplementary, "Ask 𠀀 Team today."),
                    Map.entry(micro, "Ask µ Team today."), Map.entry(unicodeWriter, "Ask Ｃｐ Writer 𠀀 today."))) {
                var resolution = resolver.resolve(sample.getValue());
                assertEquals(List.of(sample.getKey().getId()), resolution.resources().stream().map(resource -> resource.id()).toList());
                MaskingContext context = new MaskingContext();
                resolver.seed(resolution, context);
                ObjectMapper mapper = JsonMapper.builder().build();
                String payload = mapper.writeValueAsString(support.firstProviderRequest(
                        support.invocation(sample.getValue(), context, mapper), mapper).messages());
                assertFalse(payload.contains(sample.getKey().getName()));
                OutboundLeakScan.assertNoLeakStrict(payload, context, mapper);
            }
        }
    }

    private AiAssistantIdentifierResolver resolver() {
        WorkspaceService workspaceService = support.fixtureMock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess scope =
                support.fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(scope.getForWorkspace(workspace.getId())).thenReturn(new WorkspaceScope(
                workspace.getOrgId(), List.of(workspace.getId()), "[" + workspace.getId() + "]"));
        return new AiAssistantIdentifierResolver(identifierMapper, workspaceService, scope, referenceService);
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
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void queuedUserReferenceSpellingsRemainMaskedOnFirstDispatchAndFreshReplay(boolean nativeProtocol) throws Exception {
        var fixture = memberReferenceFixture();
        jdbc.update("UPDATE app_user SET display_name = 'Alice & Bob' WHERE id = ?", fixture.actor().getId());
        var pending = userMapper.getUserByUsername("pending-" + fixture.suffix());
        var foreign = userMapper.getUserByUsername("foreign-" + fixture.suffix());
        var directory = support.fixtureMock(WorkspaceService.class);
        var scope = support.fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
        when(directory.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(directory.getMembers(workspace.getId())).thenReturn(workspaceMapper.getMembers(workspace.getId()));
        when(scope.getForWorkspace(workspace.getId())).thenReturn(new WorkspaceScope(
                workspace.getOrgId(), List.of(workspace.getId()), "[" + workspace.getId() + "]"));
        var resolver = new AiAssistantIdentifierResolver(identifierMapper, directory, scope, referenceService);
        String text = "Ask [Prior Member Label](user:" + fixture.actor().getId() + ") and "
                + "[Alice &amp; Bob](user:" + fixture.actor().getId() + ") with "
                + "[Pending Alias](user:" + pending.getId() + ") and "
                + "[Foreign Alias](user:" + foreign.getId() + ") [Hidden](person:2147483647 \"title\")\n\n"
                + "| ` | [Table Member Alias][table] | ` |\n| --- | --- | --- |\n\n"
                + "See [^f].\n\n[^f]: [Footnote Pending Alias][pending]\n\n"
                + "[Container Member Alias][container] [Continuation Member Alias][continued]\n\n"
                + "[table]: user:" + fixture.actor().getId() + "\n\n[pending]: user:" + pending.getId()
                + "\n\n> [container]:\n> user:" + fixture.actor().getId()
                + "\n\n[continued]:\ruser:" + fixture.actor().getId();
        var resolution = resolver.resolve(text);
        assertTrue(resolution.resources().isEmpty());
        assertEquals(Set.of("Prior Member Label", "Alice & Bob", "Alice &amp; Bob", "Pending Alias",
                        "Table Member Alias", "Footnote Pending Alias", "Container Member Alias", "Continuation Member Alias"),
                resolution.identifiers().stream().map(AiAssistantIdentifierResolver.Identifier::value)
                        .collect(Collectors.toSet()));
        var mapper = JsonMapper.builder().build();
        var user = new AiChatMessage();
        user.setAuthorKind("user");
        user.setContent(text);
        user.setStructuredJson(mapper.writeValueAsString(Map.of("kind", "user_message", "resources", List.of(),
                "identifiers", List.of(), "memberIdentifiers", resolution.identifiers().stream()
                        .map(identifier -> Map.of("kind", "member", "value", identifier.value())).toList())));
        jdbc.update("UPDATE app_user SET display_name = 'Later Member Label' WHERE id = ?", fixture.actor().getId());
        var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        for (int replay = 0; replay < 2; replay++) {
            var context = new MaskingContext();
            if (replay == 0) {
                resolver.seed(resolution, context);
            }
            var resources = new AiChatResourceRegistry(context);
            var empty = new AiAssistantToolResult(Map.of(), List.of());
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(user), empty, List.of(), context, resources, List.of(),
                            new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(user), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(capture.request().messages());
            for (String label : List.of("Prior Member Label", "Alice &amp; Bob", "Alice & Bob", "Pending Alias",
                    "Table Member Alias", "Footnote Pending Alias", "Container Member Alias", "Continuation Member Alias")) {
                assertFalse(payload.contains(label), label);
            }
            for (String label : List.of("Prior Member Label", "Alice &amp; Bob", "Pending Alias",
                    "Table Member Alias", "Footnote Pending Alias", "Container Member Alias", "Continuation Member Alias")) {
                String token = context.tokenBindings().stream().filter(entry -> label.equals(entry.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElseThrow();
                assertTrue(payload.contains(token));
                assertEquals(label, Demasker.demask(token, context).text());
            }
            assertEquals(0, capture.warnings());
            assertFalse(context.identifierDictionary().contains("Foreign Alias"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void parserOnlyStoredLabelsStayMaskedBeforeProviderSerialization(boolean nativeProtocol) throws Exception {
        var fixture = memberReferenceFixture();
        var pending = userMapper.getUserByUsername("pending-" + fixture.suffix());
        String content = "x".repeat(490) + "\n\n| ` | [Table Stored Alias][table] | ` |\n| --- | --- | --- |\n\n"
                + "See [^f].\n\n[^f]: [Footnote Stored Alias][pending]\n\n[Container Stored Alias][container]\n\n"
                + "[table]: user:" + fixture.actor().getId() + "\n\n[pending]: user:" + pending.getId()
                + "\n\n> [container]:\n> user:" + fixture.actor().getId();
        Integer noteId = jdbc.queryForObject("SELECT id FROM note WHERE workspace_id = ? AND person_id = ?", Integer.class,
                workspace.getId(), fixture.person().getId());
        assertTrue(noteId != null);
        jdbc.update("UPDATE note SET content = ? WHERE workspace_id = ? AND id = ?", content, workspace.getId(), noteId);
        referenceService.syncReferences(workspace.getId(), noteId, content);
        assertTrue(referenceService.referencesFor(workspace.getId(), "note", noteId).isEmpty());
        var mapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        for (int replay = 0; replay < 2; replay++) {
            var context = new MaskingContext();
            var resources = new AiChatResourceRegistry(context);
            resources.register("person", fixture.person().getId());
            var result = realToolExecutor.execute("get_record", mapper.readTree("{\"handle\":\"r1\"}"), resources, true);
            for (String label : List.of("Table Stored Alias", "Footnote Stored Alias", "Container Stored Alias")) {
                assertTrue(context.identifierDictionary().contains(label), label);
                assertFalse(mapper.writeValueAsString(result.data()).contains(label), label);
            }
            String projected = mapper.valueToTree(result.data()).at("/notes/0/content").asString();
            assertTrue(projected.startsWith("x".repeat(490)));
            assertTrue(projected.contains("| --- | --- | --- |"));
            assertTrue(projected.contains("See [^f]."));
            assertEquals(3, projected.split(java.util.regex.Pattern.quote(MaskingEngine.REDACTED), -1).length - 1);
            var budget = new AiAssistantPromptBudget(64, 4_096, 16_000, 16_000, 16_000, 64_000);
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(), budget,
                            AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(capture.request().messages());
            for (String label : List.of("Table Stored Alias", "Footnote Stored Alias", "Container Stored Alias")) {
                assertFalse(payload.contains(label), label);
            }
            assertTrue(payload.contains("x".repeat(490)));
            assertTrue(payload.contains("| --- | --- | --- |"));
            assertTrue(payload.contains("See [^f]."));
            assertTrue(payload.split(java.util.regex.Pattern.quote(MaskingEngine.REDACTED), -1).length - 1 >= 3);
            assertEquals(0, capture.warnings());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void tableDisagreementsRefuseOrMaskStoredAliasesBeforeProviderSerialization(boolean nativeProtocol) throws Exception {
        var fixture = memberReferenceFixture();
        Integer noteId = jdbc.queryForObject("SELECT id FROM note WHERE workspace_id = ? AND person_id = ?", Integer.class,
                workspace.getId(), fixture.person().getId());
        assertTrue(noteId != null);
        var mapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        for (boolean refused : List.of(true, false)) {
            String label = refused ? "Stored `]` Member" : "Stored|Member";
            String content = refused
                    ? "| h |\n| - |\n|\nprefix `\n[" + label + "](user:" + fixture.actor().getId() + " \"title\")\nsuffix `"
                    : "prefix `\n\nh|i\n--|--|--\n[" + label + "](user:" + fixture.actor().getId() + " \"title\")\n`";
            jdbc.update("UPDATE note SET content = ? WHERE workspace_id = ? AND id = ?", content, workspace.getId(), noteId);
            referenceService.syncReferences(workspace.getId(), noteId, content);
            assertTrue(referenceService.referencesFor(workspace.getId(), "note", noteId).isEmpty());
            var context = new MaskingContext();
            var resources = new AiChatResourceRegistry(context);
            resources.register("person", fixture.person().getId());
            var result = realToolExecutor.execute("get_record", mapper.readTree("{\"handle\":\"r1\"}"), resources, true);
            if (refused) {
                assertEquals("(unavailable reference)", mapper.valueToTree(result.data()).at("/notes/0/content").asString());
                assertFalse(context.identifierDictionary().contains(label));
            } else {
                assertTrue(context.identifierDictionary().contains(label));
            }
            assertFalse(mapper.writeValueAsString(result.data()).contains("Stored"));
            var budget = new AiAssistantPromptBudget(64, 4_096, 16_000, 16_000, 16_000, 64_000);
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(), result, List.of(), context, resources, List.of(), budget,
                            AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(), result, List.of(), context, resources, AiAssistantToolCatalog.ALL);
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String payload = mapper.writeValueAsString(capture.request().messages());
            assertFalse(payload.contains("Stored"));
            assertTrue(payload.contains(refused ? "(unavailable reference)" : MaskingEngine.REDACTED));
            assertEquals(0, capture.warnings());
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void normalizedStoredMemberDestinationsRefuseBeforeProviderProjectionAdmission() throws Exception {
        var fixture = memberReferenceFixture();
        Integer noteId = jdbc.queryForObject("SELECT id FROM note WHERE workspace_id = ? AND person_id = ?", Integer.class,
                workspace.getId(), fixture.person().getId());
        assertTrue(noteId != null);
        String id = String.valueOf(fixture.actor().getId());
        String wideId = id.chars().mapToObj(digit -> String.valueOf((char) ('０' + digit - '0')))
                .collect(java.util.stream.Collectors.joining());
        String arabicId = id.chars().mapToObj(digit -> String.valueOf((char) ('٠' + digit - '0')))
                .collect(java.util.stream.Collectors.joining());
        var mapper = JsonMapper.builder().build();
        for (String destination : List.of("user:" + wideId, "user:" + arabicId, "UsEr:" + id,
                "user\\&colon;" + id, "user&#92;:" + id, "user：" + id, "ｕｓｅｒ:" + id, "ｕｓｅｒ：" + wideId)) {
            String content = "| h |\n| - |\n|\nprefix `\n[Stored `]` Member](" + destination + " \"title\")\nsuffix `";
            jdbc.update("UPDATE note SET content = ? WHERE workspace_id = ? AND id = ?", content, workspace.getId(), noteId);
            var context = new MaskingContext();
            var resources = new AiChatResourceRegistry(context);
            resources.register("person", fixture.person().getId());
            var result = realToolExecutor.execute("get_record", mapper.readTree("{\"handle\":\"r1\"}"), resources, true);
            assertEquals("(unavailable reference)", mapper.valueToTree(result.data()).at("/notes/0/content").asString(),
                    destination);
            assertFalse(mapper.writeValueAsString(result.data()).contains("Stored"), destination);
            assertFalse(context.identifierDictionary().contains("Stored `]` Member"));
            assertEquals(java.util.Set.of(fixture.person().getName(), fixture.person().getCompany().getName()),
                    context.tokenBindings().stream().map(java.util.Map.Entry::getValue)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(context.tokenBindings().stream().noneMatch(binding -> binding.getKey().startsWith("{{M")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void realMemberDirectoryAndAuthoredReferenceLabelsStayMaskedAcrossRenameAndFreshReplay(boolean nativeProtocol)
            throws Exception {
        MemberReferenceFixture fixture = memberReferenceFixture();
        Person person = fixture.person();
        var actor = fixture.actor();
        String suffix = fixture.suffix();
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantPromptAssembler assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        for (int replay = 0; replay < 2; replay++) {
            MaskingContext context = new MaskingContext();
            AiAssistantMemberIdentifiers.seed(workspaceMapper.getMembers(workspace.getId()), context);
            assertTrue(context.identifierDictionary().contains("Renamed Member Label"));
            assertTrue(context.identifierDictionary().contains("fallback-" + suffix));
            assertFalse(context.identifierDictionary().contains("Pending Member Label"));
            assertFalse(context.identifierDictionary().contains("Foreign Member Label"));
            assertEquals(0, context.tokenBindings().size());
            AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
            resources.register("person", person.getId());
            AiAssistantToolResult result = realToolExecutor.execute("get_record", mapper.readTree("{\"handle\":\"r1\"}"), resources, true);
            for (String label : List.of("Prior Member Label", "Other Prior Label", "Pending Member Label", "Pending Alias")) {
                assertTrue(context.identifierDictionary().contains(label), label);
            }
            String projected = mapper.writeValueAsString(result.data());
            assertFalse(projected.contains("Prior"));
            assertFalse(projected.contains("Other Prior"));
            assertFalse(projected.contains("Pending Member"));
            assertFalse(projected.contains("Pending Alias"));
            var readArguments = mapper.readTree("{\"handle\":\"r1\",\"limit\":10}");
            var activityResult = realToolExecutor.execute("list_activities", readArguments, resources, true);
            var taskResult = realToolExecutor.execute("list_tasks", readArguments, resources, true);
            var scopeResult = scopeReadService.scopeActivities(AiChatQueryScope.none(), "person", null, List.of(), 30, 50, 5, resources);
            var commitments = workBriefReadService.workCommitments(actor.getId(), 7, 10, resources);
            assertEquals(1, commitments.data().get("returnedCommitments"));
            String commitmentsText = mapper.writeValueAsString(commitments.data());
            assertFalse(commitmentsText.contains("Prior"));
            assertFalse(commitmentsText.contains("Pending"));
            String scopeText = mapper.writeValueAsString(scopeResult.data());
            assertFalse(scopeText.contains("Prior Member"));
            assertFalse(scopeText.contains("Pending Member"));
            var turn = AiAssistantPromptAssembler.ToolTurn.soleCall(1, "get_record", result);
            var activityTurn = AiAssistantPromptAssembler.ToolTurn.soleCall(2, "list_activities", activityResult);
            var taskTurn = AiAssistantPromptAssembler.ToolTurn.soleCall(3, "list_tasks", taskResult);
            var turns = List.of(turn, activityTurn, taskTurn);
            var budget = new AiAssistantPromptBudget(64, 4_096, 16_000, 16_000, 16_000, 64_000);
            var empty = new AiAssistantToolResult(Map.of(), List.of());
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(), empty, turns, context, resources, List.of(), budget, AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(), empty, turns, context, resources, AiAssistantToolCatalog.ALL);
            var exchanges = nativeProtocol ? assembler.nativeReplay(turns,
                    Map.of(turn.ref(), new ooo.klae.connex.backend.ai.provider.AiToolCall("read-note", "get_record", "{\"handle\":\"r1\"}"),
                            activityTurn.ref(), new ooo.klae.connex.backend.ai.provider.AiToolCall("read-activity", "list_activities", "{\"handle\":\"r1\",\"limit\":10}"),
                            taskTurn.ref(), new ooo.klae.connex.backend.ai.provider.AiToolCall("read-task", "list_tasks", "{\"handle\":\"r1\",\"limit\":10}")),
                    context, budget, null).exchanges() : List.<ooo.klae.connex.backend.ai.provider.AiToolExchange>of();
            String modelFinal = context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Prior Member Label")
                    + " and " + context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Other Prior Label")
                    + " met " + context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Pending Member Label")
                    + " and " + context.tokenFor(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Pending Alias") + ".";
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                    AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, modelFinal), false, null);
            String payload = mapper.writeValueAsString(capture.request().messages()) + mapper.writeValueAsString(capture.request().nativeTools());
            for (String label : List.of("Prior Member", "Other Prior", "Pending Member", "Pending Alias", "Renamed Member", "Foreign Member")) {
                assertFalse(payload.contains(label), label);
            }
            assertTrue(payload.contains("{{M1}}"));
            String finalText = "Prior Member Label and Other Prior Label met Pending Member Label and Pending Alias.";
            List<String> provenance = capture.memberIdentifiers().getOrDefault(nativeProtocol ? "/text" : "/final/text", List.of());
            assertEquals(Set.of("Prior Member Label", "Other Prior Label", "Pending Member Label", "Pending Alias"), Set.copyOf(provenance));
            AiChatMessage priorAnswer = new AiChatMessage();
            priorAnswer.setAuthorKind("assistant");
            priorAnswer.setContent(finalText);
            priorAnswer.setStructuredJson(assembler.finalMetadata(1, List.of(), List.of(), resources.snapshot(), Map.of(),
                    AiAssistantPromptAssembler.ToolBudgetAudit.NONE, null, List.of(), List.of(), provenance));
            MaskingContext fresh = new MaskingContext();
            var freshResources = new AiChatResourceRegistry(fresh);
            var page = realToolExecutor.pageContext(assembler.replayPageContext(List.of(priorAnswer)), freshResources);
            var replayPrompt = nativeProtocol
                    ? assembler.assembleNative(List.of(priorAnswer), page, List.of(), fresh, freshResources, List.of(), budget, AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(priorAnswer), page, List.of(), fresh, freshResources, AiAssistantToolCatalog.ALL);
            var replayCapture = support.captureAssistantPayload(replayPrompt, fresh, mapper, nativeProtocol, List.of(), AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
            String replayPayload = mapper.writeValueAsString(replayCapture.request().messages());
            for (String label : provenance) {
                assertFalse(replayPayload.contains(label), label);
            }
            assertTrue(replayPayload.contains("{{M1}}"));
        }
    }


    @ParameterizedTest
    @CsvSource({"complete_task,false", "complete_task,true", "reschedule_task,false", "reschedule_task,true"})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void storedTaskOutcomesMaskRenamedAndPendingReferencesOnBothProtocolsAndFreshReplay(
            String tool, boolean nativeProtocol) throws Exception {
        MemberReferenceFixture fixture = memberReferenceFixture();
        var storedTask = referenceService.hydrateTasks(workspace.getId(),
                List.of(taskMapper.getTaskById(workspace.getId(), fixture.task().getId()))).getFirst();
        assertTrue(storedTask.getDescription().contains("[Prior Member Label](user:"));
        assertTrue(storedTask.getDescription().contains("[Pending Member Label](user:"));
        var delegate = mock(ooo.klae.connex.backend.services.TaskService.class);
        when(delegate.complete(storedTask.getId())).thenReturn(storedTask);
        when(delegate.reschedule(storedTask.getId(), "2026-10-15")).thenReturn(storedTask);
        AiAssistantWriteTool declared = "complete_task".equals(tool)
                ? new AiAssistantCompleteTaskWriteTool(delegate) : new AiAssistantRescheduleTaskWriteTool(delegate);
        AiAssistantWriteToolRequest request = "complete_task".equals(tool)
                ? new AiAssistantWriteToolRequest.CompleteTask("t1")
                : new AiAssistantWriteToolRequest.RescheduleTask("t1", "2026-10-15");
        var outcome = declared.apply(new AiAssistantWriteTool.Execution(
                new AiAssistantWriteTool.Authority(workspace.getId(), fixture.actor().getId(), 29,
                        java.time.Instant.parse("2026-10-13T00:00:00Z")),
                new AiAssistantWriteTool.Row(new AiAssistantWriteTool.Target("task", storedTask.getId()), request),
                List.of(), null, new AiAssistantWriteTool.LockedTarget(null, null), (start, end) -> {
                    throw new AssertionError("Task writes cannot read schedule conflicts");
                }));
        ObjectMapper mapper = JsonMapper.builder().build();
        String storedJson = mapper.writeValueAsString(Map.of("tier", "confirm", "outcome", outcome.data()));
        assertFalse(storedJson.contains("{{M"));
        var write = new AiAssistantPreparedWrite(tool, AiAssistantToolCatalog.ToolTier.CONFIRM,
                "task", storedTask.getId(), "{}");
        var proposal = new AiAssistantToolProposal(29, "executed", storedJson, true);
        var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        var budget = new AiAssistantPromptBudget(64, 4_096, 16_000, 16_000, 16_000, 64_000);
        for (int replay = 0; replay < 2; replay++) {
            var result = writeToolService.proposalResult(write, proposal);
            assertEquals(storedTask.getDescription(), ((Map<?, ?>) result.data().get("outcome")).get("description"));
            var context = new MaskingContext();
            AiAssistantMemberIdentifiers.seed(workspaceMapper.getMembers(workspace.getId()), context);
            assertFalse(context.identifierDictionary().contains("Prior Member Label"));
            assertFalse(context.identifierDictionary().contains("Pending Member Label"));
            result.identifiers().forEach(identifier -> identifier.seed(context));
            assertEquals(0, context.tokenBindings().size());
            assertTrue(context.identifierDictionary().contains("Prior Member Label"));
            assertTrue(context.identifierDictionary().contains("Pending Member Label"));
            var resources = new AiChatResourceRegistry(context);
            var turn = AiAssistantPromptAssembler.ToolTurn.soleCall(1, tool, result);
            var turns = List.of(turn);
            var empty = new AiAssistantToolResult(Map.of(), List.of());
            var prompt = nativeProtocol
                    ? assembler.assembleNative(List.of(), empty, turns, context, resources, List.of(), budget,
                            AiAssistantToolCatalog.ALL)
                    : assembler.assemble(List.of(), empty, turns, context, resources, AiAssistantToolCatalog.ALL);
            var exchanges = nativeProtocol ? assembler.nativeReplay(turns,
                    Map.of(turn.ref(), new ooo.klae.connex.backend.ai.provider.AiToolCall("task-outcome", tool,
                            mapper.writeValueAsString(request))), context, budget, null).exchanges()
                    : List.<ooo.klae.connex.backend.ai.provider.AiToolExchange>of();
            String answer = "{\"text\":\"{{M1}} and {{M3}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
            var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                    nativeProtocol ? answer : "{\"tool\":null,\"final\":" + answer + "}", false, null);
            String payload = mapper.writeValueAsString(capture.request().messages())
                    + mapper.writeValueAsString(capture.request().nativeTools());
            for (String label : List.of("Prior Member Label", "Other Prior Label", "Pending Member Label", "Pending Alias")) {
                assertFalse(payload.contains(label), label);
            }
            assertTrue(payload.contains("{{M1}}"));
            assertTrue(payload.contains("{{M3}}"));
            assertEquals(0, capture.warnings());
            assertEquals("Prior Member Label and Pending Member Label", Demasker.demask("{{M1}} and {{M3}}", context).text());
            assertEquals(4, context.tokenBindings().size());
            String guessed = "{\"text\":\"{{M5}}\",\"citations\":[],\"suggestions\":[],\"title\":null}";
            var refused = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, exchanges,
                    nativeProtocol ? guessed : "{\"tool\":null,\"final\":" + guessed + "}", false, null);
            assertEquals(1, refused.warnings());
            var demaskedGuess = Demasker.demask("{{M5}}", context);
            assertEquals(1, demaskedGuess.warnings());
            for (String label : List.of("Prior Member Label", "Pending Member Label", "Renamed Member Label")) {
                assertFalse(demaskedGuess.text().contains(label));
            }
            assertEquals(4, context.tokenBindings().size());
        }
        verify(delegate, org.mockito.Mockito.times("complete_task".equals(tool) ? 1 : 0)).complete(storedTask.getId());
        verify(delegate, org.mockito.Mockito.times("reschedule_task".equals(tool) ? 1 : 0))
                .reschedule(storedTask.getId(), "2026-10-15");
        assertTrue(storedJson.contains("Prior Member Label"));
        assertTrue(storedJson.contains("Pending Member Label"));
    }

    private MemberReferenceFixture memberReferenceFixture() {
        String suffix = unique();
        Workspace foreign = new Workspace();
        foreign.setOrgId(workspace.getOrgId());
        foreign.setName("Foreign " + suffix);
        foreign.setSlug("foreign-" + suffix);
        workspaceMapper.insert(foreign);
        committedForeignWorkspace = foreign;
        committedMemberUsernames = List.of("active-" + suffix, "pending-" + suffix, "foreign-" + suffix, "fallback-" + suffix);
        jdbc.update("INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES "
                        + "(?, 'Prior Member Label', ?, 'fixture-hash', 'UTC'), "
                        + "(?, 'Pending Member Label', ?, 'fixture-hash', 'UTC'), "
                        + "(?, 'Foreign Member Label', ?, 'fixture-hash', 'UTC'), "
                        + "(?, '', ?, 'fixture-hash', 'UTC')",
                "active-" + suffix, "active-" + suffix + "@example.test",
                "pending-" + suffix, "pending-" + suffix + "@example.test",
                "foreign-" + suffix, "foreign-" + suffix + "@example.test",
                "fallback-" + suffix, "fallback-" + suffix + "@example.test");
        jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) "
                        + "SELECT IF(username = ?, ?, ?), id, 'owner', IF(username = ?, 'pending', 'active') "
                        + "FROM app_user WHERE username IN (?, ?, ?, ?)",
                "foreign-" + suffix, foreign.getId(), workspace.getId(), "pending-" + suffix,
                "active-" + suffix, "pending-" + suffix, "foreign-" + suffix, "fallback-" + suffix);
        var actor = userMapper.getUserByUsername("active-" + suffix);
        var pending = userMapper.getUserByUsername("pending-" + suffix);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(workspace.getId(), workspace.getOrgId(), actor.getId(), "owner", null);
        Person person = newPerson(newCompany("Reference Fixture"), "Reference Contact");
        ooo.klae.connex.backend.beans.Note note = new ooo.klae.connex.backend.beans.Note();
        note.setWorkspaceId(workspace.getId());
        note.setAuthor(actor);
        note.setPerson(person);
        note.setVisibility("workspace");
        note.setTitle("Reference coverage");
        String authored = "x".repeat(490) + " [Prior Member Label](user:" + actor.getId() + ") "
                + "[Other Prior Label](user:" + actor.getId() + ") "
                + "[Pending Member Label](user:" + pending.getId() + ") "
                + "[Pending Alias][invite]\n\n[invite]: user:" + pending.getId();
        note.setContent(authored);
        noteMapper.insert(note);
        referenceService.syncReferences(workspace.getId(), note.getId(), authored);
        var activity = new ooo.klae.connex.backend.beans.Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setCreatedBy(actor);
        activity.setPerson(person);
        activity.setType("meeting");
        activity.setSubject("Reference meeting");
        activity.setNotes(authored);
        activity.setTimestamp("2026-10-13 00:00:00");
        activityMapper.insert(activity);
        referenceService.syncReferences(workspace.getId(), ooo.klae.connex.backend.services.ReferenceService.SOURCE_ACTIVITY,
                activity.getId(), authored);
        var task = new ooo.klae.connex.backend.beans.Task();
        task.setWorkspaceId(workspace.getId());
        task.setAssignedTo(actor);
        task.setPerson(person);
        task.setDescription(authored);
        task.setStatus("todo");
        task.setDueDate("2026-10-12");
        taskMapper.insert(task);
        referenceService.syncReferences(workspace.getId(), ooo.klae.connex.backend.services.ReferenceService.SOURCE_TASK,
                task.getId(), authored);
        jdbc.update("UPDATE app_user SET display_name = 'Renamed Member Label' WHERE id = ?", actor.getId());
        return new MemberReferenceFixture(person, actor, task, suffix);
    }

    private record MemberReferenceFixture(
            Person person, ooo.klae.connex.backend.beans.User actor,
            ooo.klae.connex.backend.beans.Task task, String suffix) {
    }

    @Test
    void sharedNoteQueriesExcludePrivateRowsBeforeApplyingTheLimit() {
        Company company = newCompany("Privacy parent");
        Person person = newPerson(company, "Privacy contact");
        String username = "privacy-" + unique();
        jdbc.update("INSERT INTO app_user (username, display_name, email, password_hash, timezone) "
                + "VALUES (?, 'Privacy actor', ?, 'fixture-hash', 'UTC')", username, username + "@example.test");
        var actor = userMapper.getUserByUsername(username);
        jdbc.update("INSERT INTO note (workspace_id, person_id, author_id, visibility, title, content, created_at, updated_at) "
                + "SELECT ?, ?, ?, IF(sequence.n < 12, 'private', 'workspace'), CONCAT('note-', sequence.n), 'Visible prose', "
                + "TIMESTAMP('2026-10-13') - INTERVAL sequence.n MINUTE, "
                + "TIMESTAMP('2026-10-13') - INTERVAL sequence.n MINUTE "
                + "FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 "
                + "UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13) sequence",
                workspace.getId(), person.getId(), actor.getId());
        assertEquals(2, noteMapper.getVisibleNotesByPersonId(workspace.getId(), person.getId(), actor.getId(), 10, 0, null, false).size());
        assertEquals(10, noteMapper.getVisibleNotesByPersonId(workspace.getId(), person.getId(), actor.getId(), 10, 0, null, true).size());
        assertEquals(2, noteMapper.getAiAssistantVisibleNotesByCompanyId(workspace.getId(), company.getId(), actor.getId(),
                List.of(workspace.getId()), 10, false).size());
        assertEquals(10, noteMapper.getVisibleNotesByPersonId(workspace.getId(), person.getId(), actor.getId(), 10, 0).size());
        Deal deal = newDeal(company, "Privacy deal");
        jdbc.update("UPDATE note SET deal_id = ? WHERE workspace_id = ? AND person_id = ?", deal.getId(), workspace.getId(), person.getId());
        assertEquals(2, noteMapper.getVisibleNotesByDealId(workspace.getId(), deal.getId(), actor.getId(), 10, 0, null, false).size());
        assertEquals(10, noteMapper.getVisibleNotesByDealId(workspace.getId(), deal.getId(), actor.getId(), 10, 0, null, true).size());
    }

}

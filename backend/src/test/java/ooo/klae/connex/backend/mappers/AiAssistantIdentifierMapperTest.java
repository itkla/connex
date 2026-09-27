package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class AiAssistantIdentifierMapperTest extends AbstractMapperTest {
    @Autowired private AiAssistantIdentifierMapper identifierMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private OrganizationMapper organizationMapper;

    /**
     * Uses a fresh organization and workspace per test. The prefilter is workspace-global, so a
     * shared default workspace would let records left by sibling tests change the match count.
     */
    @Override
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

    @Test
    void lookupCombinesVisibleKindsAsLiteralSubstringsUnderOneGlobalLimit() {
        Company company = newCompany();
        company.setName("Acme Corp");
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName("Kenji Sato");
        personMapper.update(person);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setName("Renewal Plan");
        dealMapper.update(deal);

        var matches = identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Ask Kenji Sato about Acme Corp and Renewal Plan",
                201, "", 0);

        assertEquals(List.of("company", "deal", "person"), matches.stream()
                .map(match -> match.getKind())
                .sorted()
                .toList());
        assertEquals(3, identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Kenji Satomi at Acme Corporation discussed Renewal Planning",
                201, "", 0).size());
        assertEquals(2, identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Ask Kenji Sato about Acme Corp and Renewal Plan",
                2, "", 0).size());
        assertEquals(1, companyMapper.archive(workspace.getId(), company.getId()));
        var lastPage = identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]",
                "Ask Kenji Sato about Acme Corp and Renewal Plan", 2, "deal", deal.getId());
        assertEquals(List.of(matches.getLast().getId()), lastPage.stream()
                .map(match -> match.getId()).toList());
    }

    @Test
    void lookupFoldsSeparatorRunsOnBothSidesOfTheComparison() {
        Company company = newCompany();
        company.setName("Acme Corp");
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName("John O'Connor");
        personMapper.update(person);
        Person spaced = newPerson(company);
        spaced.setName("Anne-Marie  Smith");
        personMapper.update(spaced);

        for (String text : List.of(
                "What about John\nO'Connor at Acme\nCorp?",
                "What about John  O'Connor at Acme\tCorp?",
                "What about John \n O'Connor at Acme \t Corp?",
                "What about John\u0007O'Connor at Acme\u0001Corp?")) {
            assertEquals(2, identifierMapper.findMentionedRecords(
                    workspace.getId(), "[" + workspace.getId() + "]", text, 201, "", 0).size(), text);
        }
        assertEquals(1, identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Ask Anne-Marie Smith about it",
                201, "", 0).size());
    }

    @Test
    void storedRecordLinksRemainCandidatesForTheirPreprocessedLabelsAcrossVisibleKinds() {
        Company company = newCompany();
        company.setName("John [O'Connor](person:999)");
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName("[John](record:r1) O'Connor");
        personMapper.update(person);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setName("[John O'](company:999)Connor");
        dealMapper.update(deal);
        MaskingEngine.MentionScanText scan = MaskingEngine.mentionScanText("Ask John O'Connor today.");

        var matches = identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", scan.normalizedText(), 200, "", 0);

        assertEquals(List.of("company", "deal", "person"), matches.stream()
                .map(match -> match.getKind()).sorted().toList());
        assertTrue(matches.stream().allMatch(match -> MaskingEngine.containsIdentifierMention(scan, match.getValue())));
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
            "i\u0307pek Smith|Ipek Smith",
            "NIKO\u03c2 Team|NIKO\u03c3 Team"
    })
    void lookupAdmitsSimpleUnicodeCaseVariantsAcrossVisibleKinds(String name, String spelling) {
        Company company = newCompany();
        company.setName(name);
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName(name);
        personMapper.update(person);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setName(name);
        dealMapper.update(deal);
        String canonicalTurn = MaskingEngine.mentionScanText("Ask " + spelling + " today.").normalizedText();

        var matches = identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", canonicalTurn, 201, "", 0);

        assertEquals(List.of("company", "deal", "person"), matches.stream()
                .map(match -> match.getKind()).sorted().toList());
        assertTrue(matches.stream().allMatch(match ->
                MaskingEngine.containsIdentifierMention("Ask " + spelling + " today.", match.getValue())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{}}", "\uFF5B\uFF5B\uFF5D\uFF5D"})
    void lookupSanitizesDelimitersOnBothSidesAcrossVisibleKinds(String delimiters) {
        Company company = newCompany();
        company.setName("John O'" + delimiters + "Connor");
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName(company.getName());
        personMapper.update(person);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage, company);
        deal.setName("John " + delimiters + " O'Connor");
        dealMapper.update(deal);

        for (String text : List.of("What is happening with John O'Connor?",
                "What is happening with John O'" + delimiters + "Connor?")) {
            var matches = identifierMapper.findMentionedRecords(
                    workspace.getId(), "[" + workspace.getId() + "]", text, 201, "", 0);
            assertEquals(List.of("company", "deal", "person"), matches.stream()
                    .map(match -> match.getKind()).sorted().toList());
        }
    }

    @Test
    void lookupWidensUnicodeCandidatesForJavaAdmission() {
        Person person = newPerson(newCompany());
        person.setName("\u0307 \u0307 \u0307 \u0307");
        personMapper.update(person);

        assertEquals(1, identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", "An unrelated note", 201, "", 0).size());
        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", "Ask " + person.getName() + " today.", 201, "", 0)
                .stream().map(match -> match.getId()).toList().contains(person.getId()));
    }

    /**
     * A turn carrying one non-ASCII character — any Japanese turn, a typographic apostrophe, an
     * accent — must not admit records it does not mention. The turn operand arrives already
     * canonicalized from the resolver, so only the candidate column needs the widening fallback;
     * a turn-side fallback returned the whole visible corpus for ordinary Japanese usage.
     */
    @Test
    void aNonAsciiTurnDoesNotAdmitUnmentionedAsciiCandidates() {
        Company company = newCompany();
        company.setName("Acme Corp");
        companyMapper.update(company);
        Person person = newPerson(company);
        person.setName("Kenji Sato");
        personMapper.update(person);

        for (String text : List.of("今日の予定は？", "what’s happening with the pipeline",
                "café deal", "日本語のメモ")) {
            assertTrue(identifierMapper.findMentionedRecords(
                    workspace.getId(), "[" + workspace.getId() + "]", text, 201, "", 0).isEmpty(), text);
        }
        assertEquals(1, identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]",
                "今日はkenji satoと会う", 201, "", 0).size());
    }

    @Test
    void lookupDropsEmptyCanonicalBraceOnlyCandidates() {
        Person person = newPerson(newCompany());
        person.setName("{}}{}{}{}");
        personMapper.update(person);

        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", "Ask }{}{} today.", 201, "", 0).isEmpty());
        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", "An unrelated turn", 201, "", 0).isEmpty());
    }

    @Test
    void lookupTreatsRegexMetacharactersAsLiteralNameData() {
        Company company = newCompany();
        company.setName("A.*[B](C)");
        companyMapper.update(company);

        assertEquals(1, identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Ask A.*[B](C) about renewal",
                201, "", 0).size());
        String unrelated = "Ask AxxxxBC about renewal";
        var candidates = identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                unrelated,
                201, "", 0);
        assertEquals(1, candidates.size());
        assertTrue(candidates.stream().noneMatch(candidate ->
                MaskingEngine.containsIdentifierMention(unrelated, candidate.getValue())));
    }

    @Test
    void lookupExcludesNamesOwnedByAnotherUnsharedWorkspace() {
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        if (orgId == null) {
            throw new IllegalStateException("Test workspace organization is unavailable");
        }
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(orgId);
        workspaceMapper.insert(sibling);
        Company hiddenCompany = new Company();
        hiddenCompany.setWorkspaceId(sibling.getId());
        hiddenCompany.setName("Hidden Company " + unique());
        companyMapper.insert(hiddenCompany);
        Person hiddenPerson = new Person();
        hiddenPerson.setWorkspaceId(sibling.getId());
        hiddenPerson.setName("Hidden Person " + unique());
        hiddenPerson.setCompany(hiddenCompany);
        personMapper.insert(hiddenPerson);

        String text = hiddenPerson.getName() + " at " + hiddenCompany.getName();

        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "," + sibling.getId() + "]",
                text,
                201, "", 0).isEmpty());
    }

    @Test
    void lookupRequiresSharedRecordsToBelongToTheControlDerivedOrganizationScope() {
        User actor = newUser();
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        if (orgId == null) {
            throw new IllegalStateException("Test workspace organization is unavailable");
        }
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(orgId);
        workspaceMapper.insert(sibling);
        Company sharedCompany = new Company();
        sharedCompany.setWorkspaceId(sibling.getId());
        sharedCompany.setName("Shared Company " + unique());
        companyMapper.insert(sharedCompany);
        Person sharedPerson = new Person();
        sharedPerson.setWorkspaceId(sibling.getId());
        sharedPerson.setName("Shared Person " + unique());
        sharedPerson.setCompany(sharedCompany);
        personMapper.insert(sharedPerson);
        assertTrue(shareMapper.shareCompany(
                sharedCompany.getId(), sibling.getId(), workspace.getId(), actor.getId(), false) > 0);
        assertTrue(shareMapper.sharePerson(
                sharedPerson.getId(), sibling.getId(), workspace.getId(), actor.getId(), false) > 0);
        String text = sharedPerson.getName() + " at " + sharedCompany.getName();
        String completeScope = "[" + workspace.getId() + "," + sibling.getId() + "]";

        assertEquals(2, identifierMapper.findMentionedRecords(
                workspace.getId(), completeScope, text, 201, "", 0).size());
        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", text, 201, "", 0).isEmpty());
    }
}

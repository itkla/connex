package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class AiAssistantIdentifierMapperTest extends AbstractMapperTest {
    @Autowired private AiAssistantIdentifierMapper identifierMapper;
    @Autowired private ShareMapper shareMapper;

    @Test
    void lookupCombinesVisibleKindsWithAsciiBoundariesAndOneGlobalLimit() {
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
                21);

        assertEquals(List.of("company", "deal", "person"), matches.stream()
                .map(match -> match.getKind())
                .sorted()
                .toList());
        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Kenji Satomi at Acme Corporation discussed Renewal Planning",
                21).isEmpty());
        assertEquals(2, identifierMapper.findMentionedRecords(
                workspace.getId(),
                "[" + workspace.getId() + "]",
                "Ask Kenji Sato about Acme Corp and Renewal Plan",
                2).size());
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
                21).isEmpty());
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
                workspace.getId(), completeScope, text, 21).size());
        assertTrue(identifierMapper.findMentionedRecords(
                workspace.getId(), "[" + workspace.getId() + "]", text, 21).isEmpty());
    }
}

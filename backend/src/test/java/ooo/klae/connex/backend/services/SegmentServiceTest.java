package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SegmentFieldsDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

class SegmentServiceTest extends AbstractServiceTest {

    @Autowired SegmentService segmentService;
    @Autowired PersonEdgeMapper edgeMapper;

    private static final DateTimeFormatter MYSQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SegmentCondition predicate(String key) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey(key);
        return condition;
    }

    private static SegmentCondition field(String field, String op, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(field);
        condition.setOp(op);
        condition.setValue(value);
        return condition;
    }

    private static SegmentDefinition def(String match, SegmentCondition... conditions) {
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch(match);
        definition.setConditions(List.of(conditions));
        return definition;
    }

    private List<Integer> evaluate(SegmentDefinition definition) {
        return segmentService.evaluate("company", definition);
    }

    private Company companyWithIndustry(String name, String industry) {
        Company company = new Company();
        company.setName(name);
        company.setIndustry(industry);
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }

    private void recentActivity(Person person) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("recent_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(LocalDateTime.now().format(MYSQL));
        activityMapper.insert(activity);
    }

    private void strongEdge(Person a, Person b) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(a.getId(), b.getId()));
        edge.setTargetPersonId(Math.max(a.getId(), b.getId()));
        edge.setType("knows");
        edge.setStrength(3);
        edge.setCreatedAt(LocalDateTime.now().format(MYSQL));
        edgeMapper.upsert(edge);
    }

    @Test
    void predicate_openDeal_matches() {
        Company withDeal = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        newDeal(pipeline, stage, withDeal);
        Company without = newCompany();

        List<Integer> ids = evaluate(def("all", predicate("open_deal")));

        assertTrue(ids.contains(withDeal.getId()));
        assertFalse(ids.contains(without.getId()));
    }

    @Test
    void field_industryEquals_matches() {
        Company fintech = companyWithIndustry("Acme", "Fintech");
        Company other = companyWithIndustry("Beta", "Logistics");

        List<Integer> ids = evaluate(def("all", field("industry", "equals", "Fintech")));

        assertTrue(ids.contains(fintech.getId()));
        assertFalse(ids.contains(other.getId()));
    }

    @Test
    void field_nameContains_matches() {
        Company match = companyWithIndustry("Acme Robotics", "Tech");
        Company other = companyWithIndustry("Beta Logistics", "Tech");

        List<Integer> ids = evaluate(def("all", field("name", "contains", "robot")));

        assertTrue(ids.contains(match.getId()));
        assertFalse(ids.contains(other.getId()));
    }

    @Test
    void field_hasTag_matches() {
        Company tagged = newCompany();
        Company untagged = newCompany();
        Tag tag = newTag();
        companyMapper.addTag(tagged.getId(), tag.getId());

        List<Integer> ids = evaluate(def("all", field("tag", "has", String.valueOf(tag.getId()))));

        assertTrue(ids.contains(tagged.getId()));
        assertFalse(ids.contains(untagged.getId()));
    }

    @Test
    void match_any_unionsConditions() {
        Company fintech = companyWithIndustry("Acme", "Fintech");
        Company logistics = companyWithIndustry("Beta", "Logistics");

        List<Integer> ids = evaluate(def("any",
            field("industry", "equals", "Fintech"),
            field("industry", "equals", "Logistics")));

        assertTrue(ids.contains(fintech.getId()));
        assertTrue(ids.contains(logistics.getId()));
    }

    @Test
    void match_all_intersectsConditions() {
        Company both = companyWithIndustry("Acme Robotics", "Fintech");
        Company onlyIndustry = companyWithIndustry("Beta", "Fintech");

        List<Integer> ids = evaluate(def("all",
            field("industry", "equals", "Fintech"),
            field("name", "contains", "robot")));

        assertTrue(ids.contains(both.getId()));
        assertFalse(ids.contains(onlyIndustry.getId()));
    }

    @Test
    void negate_complementsWithinWorkspace() {
        Company fintech = companyWithIndustry("Acme", "Fintech");
        Company other = companyWithIndustry("Beta", "Logistics");
        SegmentCondition notFintech = field("industry", "equals", "Fintech");
        notFintech.setNegate(true);

        List<Integer> ids = evaluate(def("all", notFintech));

        assertFalse(ids.contains(fintech.getId()));
        assertTrue(ids.contains(other.getId()));
    }

    @Test
    void warmIntro_matchesTeamConnectedCompany() {
        Company target = newCompany();
        Person contact = newPerson(target);
        Person engaged = newPerson(newCompany());
        recentActivity(engaged);
        strongEdge(contact, engaged);

        assertTrue(evaluate(def("all", predicate("warm_intro_available"))).contains(target.getId()));
    }

    @Test
    void cooling_includesUntouchedCompany() {
        Company cold = newCompany();
        assertTrue(evaluate(def("all", predicate("cooling"))).contains(cold.getId()));
    }

    @Test
    void evaluate_excludesOtherWorkspaceCompanies() {
        Workspace other = new Workspace();
        other.setName("WS " + unique());
        other.setSlug("ws_" + unique());
        workspaceMapper.insert(other);
        Company foreign = new Company();
        foreign.setName("Foreign");
        foreign.setIndustry("Fintech");
        foreign.setWorkspaceId(other.getId());
        companyMapper.insert(foreign);

        assertFalse(evaluate(def("all", field("industry", "equals", "Fintech"))).contains(foreign.getId()));
        assertFalse(evaluate(def("all", predicate("cooling"))).contains(foreign.getId()));
    }

    @Test
    void fields_returnsIndustriesAndTags() {
        companyWithIndustry("Acme", "Aerospace");
        Tag tag = newTag();

        SegmentFieldsDto fields = segmentService.fields("company");

        assertTrue(fields.getIndustries().contains("Aerospace"));
        assertTrue(fields.getTags().stream().anyMatch(option -> option.getId() == tag.getId()));
    }

    @Test
    void field_nameContains_escapesLikeWildcards() {
        Company literal = companyWithIndustry("100% Cotton", "Textile");
        Company other = companyWithIndustry("Acme Corp", "Textile");

        List<Integer> ids = evaluate(def("all", field("name", "contains", "%")));

        assertTrue(ids.contains(literal.getId()));
        assertFalse(ids.contains(other.getId()));
    }

    @Test
    void negate_combinedWithAny_unionsComplement() {
        Company fintech = companyWithIndustry("Acme", "Fintech");
        Company logistics = companyWithIndustry("Beta", "Logistics");
        SegmentCondition notFintech = field("industry", "equals", "Fintech");
        notFintech.setNegate(true);

        List<Integer> ids = evaluate(def("any", notFintech, field("industry", "equals", "Logistics")));

        assertFalse(ids.contains(fintech.getId()));
        assertTrue(ids.contains(logistics.getId()));
    }

    @Test
    void negate_excludesOtherWorkspaceFromComplement() {
        Workspace other = new Workspace();
        other.setName("WS " + unique());
        other.setSlug("ws_" + unique());
        workspaceMapper.insert(other);
        Company foreign = new Company();
        foreign.setName("Foreign");
        foreign.setIndustry("Logistics");
        foreign.setWorkspaceId(other.getId());
        companyMapper.insert(foreign);
        SegmentCondition notFintech = field("industry", "equals", "Fintech");
        notFintech.setNegate(true);

        assertFalse(evaluate(def("all", notFintech)).contains(foreign.getId()));
    }

    @Test
    void field_blankValue_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("all", field("industry", "equals", "  "))));
    }

    @Test
    void tag_nonNumericValue_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("all", field("tag", "has", "abc"))));
    }

    @Test
    void unknownPredicate_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("all", predicate("bogus"))));
    }

    @Test
    void unknownField_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("all", field("bogus", "equals", "x"))));
    }

    @Test
    void invalidMatch_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("xor", predicate("open_deal"))));
    }

    @Test
    void unsupportedRecordType_throws() {
        assertThrows(BadRequestException.class, () -> segmentService.evaluate("person", def("all", predicate("open_deal"))));
    }

    @Test
    void tooManyConditions_throws() {
        SegmentCondition[] many = new SegmentCondition[17];
        for (int i = 0; i < many.length; i++) {
            many[i] = field("name", "contains", "x" + i);
        }
        assertThrows(BadRequestException.class, () -> evaluate(def("all", many)));
    }
}

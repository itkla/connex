package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SegmentCatalogDto;
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
        companyMapper.addTag(workspace.getId(), tagged.getId(), tag.getId());

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
    void predicate_noActivity_excludesRecentlyActiveCompany() {
        Company quiet = newCompany();
        Company active = newCompany();
        recentActivity(newPerson(active));

        List<Integer> ids = evaluate(def("all", predicate("no_activity")));

        assertTrue(ids.contains(quiet.getId()));
        assertFalse(ids.contains(active.getId()));
    }

    @Test
    void warmIntro_excludesCompanyUserAlreadyEngaged() {
        Company target = newCompany();
        Person contact = newPerson(target);
        Person engaged = newPerson(newCompany());
        recentActivity(engaged);
        strongEdge(contact, engaged);
        recentActivity(contact);

        assertFalse(evaluate(def("all", predicate("warm_intro_available"))).contains(target.getId()));
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

    private Deal dealWith(Pipeline pipeline, Stage stage, Company company, double value, String closeDate) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(currentUser.getId());
        deal.setValue(value);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        deal.setExpectedCloseDate(closeDate);
        dealMapper.insert(deal);
        return deal;
    }

    @Test
    void rootNegate_complementsWholeDefinition() {
        Company fintech = companyWithIndustry("Acme", "Fintech");
        Company other = companyWithIndustry("Beta", "Logistics");
        SegmentDefinition definition = def("all", field("industry", "equals", "Fintech"));
        definition.setNegate(true);

        List<Integer> ids = evaluate(definition);

        assertFalse(ids.contains(fintech.getId()));
        assertTrue(ids.contains(other.getId()));
    }

    @Test
    void nestedGroups_mixAndOr() {
        Company match = companyWithIndustry("Acme Robotics", "Fintech");
        Company wrongName = companyWithIndustry("Beta Corp", "Fintech");
        Company wrongIndustry = companyWithIndustry("Gamma Robotics", "Logistics");
        SegmentDefinition inner = def("any", field("name", "contains", "robotics"), field("name", "contains", "widgets"));
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(field("industry", "equals", "Fintech")));
        definition.setGroups(List.of(inner));

        List<Integer> ids = evaluate(definition);

        assertTrue(ids.contains(match.getId()));
        assertFalse(ids.contains(wrongName.getId()));
        assertFalse(ids.contains(wrongIndustry.getId()));
    }

    @Test
    void dealField_valueGte_matches() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal big = dealWith(pipeline, stage, company, 1000.0, null);
        Deal small = dealWith(pipeline, stage, company, 100.0, null);

        List<Integer> ids = segmentService.evaluate("deal", def("all", field("value", "gte", "500")));

        assertTrue(ids.contains(big.getId()));
        assertFalse(ids.contains(small.getId()));
    }

    @Test
    void dealField_closeDateIsSet_matchesOnlyDatedDeals() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal dated = dealWith(pipeline, stage, company, 1000.0, "2030-01-01");
        Deal undated = dealWith(pipeline, stage, company, 1000.0, null);

        List<Integer> ids = segmentService.evaluate("deal", def("all", field("close_date", "is_set", "")));

        assertTrue(ids.contains(dated.getId()));
        assertFalse(ids.contains(undated.getId()));
    }

    @Test
    void tooManyConditions_throws() {
        SegmentCondition[] many = new SegmentCondition[33];
        for (int i = 0; i < many.length; i++) {
            many[i] = field("name", "contains", "x" + i);
        }
        assertThrows(BadRequestException.class, () -> evaluate(def("all", many)));
    }

    @Test
    void catalog_company_exposesFieldsPredicatesAndLimits() {
        SegmentCatalogDto dto = segmentService.catalog("company");

        assertEquals("company", dto.recordType());
        assertEquals(List.of("industry", "name", "website", "phone", "tag", "created", "updated"),
            dto.fields().stream().map(SegmentCatalogDto.CatalogField::field).toList());
        SegmentCatalogDto.CatalogField industry = dto.fields().stream()
            .filter(f -> f.field().equals("industry")).findFirst().orElseThrow();
        assertEquals("string", industry.kind());
        assertEquals("industries", industry.valueSource());
        assertEquals(List.of("equals", "contains", "starts_with", "is_set"), industry.operators());
        assertEquals(List.of("warm_intro_available", "open_deal", "cooling", "no_activity", "has_attachment",
                "warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold", "warmth_rising", "going_cold"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        SegmentCatalogDto.CatalogPredicate noActivity = dto.predicates().stream()
            .filter(p -> p.key().equals("no_activity")).findFirst().orElseThrow();
        assertTrue(noActivity.acceptsDays());
        assertEquals(30, noActivity.defaultDays());
        assertEquals(3650, noActivity.maxDays());
        assertFalse(dto.predicates().stream().filter(p -> p.key().equals("open_deal"))
            .findFirst().orElseThrow().acceptsDays());
        assertNotNull(dto.limits());
        assertEquals(32, dto.limits().maxConditions());
        assertEquals(4, dto.limits().maxDepth());
    }

    @Test
    void catalog_deal_hasStatusEnumOptionsAndExistencePredicates() {
        SegmentCatalogDto dto = segmentService.catalog("deal");

        assertEquals(List.of("has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment",
                "at_risk", "risk_high", "risk_close_overdue", "risk_closing_soon", "risk_stalled",
                "risk_stakeholder_cold", "risk_no_stakeholders"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        assertEquals(List.of("open", "won", "lost"), dto.enumOptions().get("status"));
        SegmentCatalogDto.CatalogField stage = dto.fields().stream()
            .filter(f -> f.field().equals("stage")).findFirst().orElseThrow();
        assertEquals("id", stage.kind());
        assertEquals("stages", stage.valueSource());
        assertEquals(List.of("is", "in"), stage.operators());
    }

    @Test
    void catalog_person_hasExistencePredicatesAndNoEnumOptions() {
        SegmentCatalogDto dto = segmentService.catalog("person");

        assertEquals(List.of("has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment",
                "warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold", "warmth_rising", "going_cold"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        assertTrue(dto.enumOptions().isEmpty());
    }

    @Test
    void catalog_unsupportedRecordType_throws() {
        assertThrows(BadRequestException.class, () -> segmentService.catalog("task"));
    }

    private Task taskFor(Person person, Deal deal, boolean completed, String dueDate) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("task " + unique());
        task.setCompleted(completed);
        task.setStatus(completed ? "done" : "todo");
        task.setPosition(0);
        task.setDueDate(dueDate);
        task.setAssignedTo(currentUser);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }

    private void meetingFor(Person person, String timestamp) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("meeting_" + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp(timestamp);
        activityMapper.insert(activity);
    }

    private void noteFor(Person person, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("note_" + unique());
        note.setVisibility(visibility);
        note.setAuthor(currentUser);
        note.setPerson(person);
        noteMapper.insert(note);
    }

    @Test
    void personPredicate_hasOpenTask_matchesOnlyIncompleteTasks() {
        Person withTask = newPerson(newCompany());
        taskFor(withTask, null, false, null);
        Person doneOnly = newPerson(newCompany());
        taskFor(doneOnly, null, true, null);

        List<Integer> ids = segmentService.evaluate("person", def("all", predicate("has_open_task")));

        assertTrue(ids.contains(withTask.getId()));
        assertFalse(ids.contains(doneOnly.getId()));
    }

    @Test
    void personPredicate_recentMeeting_respectsWindowAndType() {
        Person recent = newPerson(newCompany());
        meetingFor(recent, LocalDateTime.now().format(MYSQL));
        Person old = newPerson(newCompany());
        meetingFor(old, LocalDateTime.now().minusDays(400).format(MYSQL));
        Person callOnly = newPerson(newCompany());
        recentActivity(callOnly);

        SegmentCondition within = predicate("recent_meeting");
        within.setDays(30);
        List<Integer> ids = segmentService.evaluate("person", def("all", within));

        assertTrue(ids.contains(recent.getId()));
        assertFalse(ids.contains(old.getId()));
        assertFalse(ids.contains(callOnly.getId()));
    }

    @Test
    void personPredicate_hasNote_excludesPrivateNotes() {
        Person shared = newPerson(newCompany());
        noteFor(shared, "workspace");
        Person privateOnly = newPerson(newCompany());
        noteFor(privateOnly, "private");

        List<Integer> ids = segmentService.evaluate("person", def("all", predicate("has_note")));

        assertTrue(ids.contains(shared.getId()));
        assertFalse(ids.contains(privateOnly.getId()));
    }

    @Test
    void dealPredicate_overdueTask_matchesPastDueIncompleteTasks() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal overdue = newDeal(pipeline, stage, newCompany());
        taskFor(null, overdue, false, "2000-01-01");
        Deal future = newDeal(pipeline, stage, newCompany());
        taskFor(null, future, false, "2999-01-01");

        List<Integer> ids = segmentService.evaluate("deal", def("all", predicate("overdue_task")));

        assertTrue(ids.contains(overdue.getId()));
        assertFalse(ids.contains(future.getId()));
    }

    @Test
    void predicate_notApplicableToRecordType_throws() {
        assertThrows(BadRequestException.class,
            () -> segmentService.evaluate("person", def("all", predicate("open_deal"))));
        assertThrows(BadRequestException.class,
            () -> segmentService.evaluate("company", def("all", predicate("has_open_task"))));
        assertThrows(BadRequestException.class,
            () -> segmentService.evaluate("deal", def("all", predicate("warmth_hot"))));
    }

    @Test
    void companyPredicate_warmthCold_matchesUntouchedCompany() {
        Company cold = newCompany();

        assertTrue(evaluate(def("all", predicate("warmth_cold"))).contains(cold.getId()));
        assertFalse(evaluate(def("all", predicate("warmth_hot"))).contains(cold.getId()));
    }

    @Test
    void personPredicate_warmthCold_matchesUntouchedContact() {
        Person cold = newPerson(newCompany());

        List<Integer> coldIds = segmentService.evaluate("person", def("all", predicate("warmth_cold")));
        List<Integer> hotIds = segmentService.evaluate("person", def("all", predicate("warmth_hot")));

        assertTrue(coldIds.contains(cold.getId()));
        assertFalse(hotIds.contains(cold.getId()));
    }

    @Test
    void goingCold_excludesAlreadyColdRecords() {
        Company cold = newCompany();

        SegmentCondition goingCold = predicate("going_cold");
        goingCold.setDays(90);
        assertFalse(evaluate(def("all", goingCold)).contains(cold.getId()));
    }

    @Test
    void dealPredicate_atRisk_matchesOverdueDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal overdue = dealWith(pipeline, stage, newCompany(), 1000.0, "2000-01-01");
        Deal future = dealWith(pipeline, stage, newCompany(), 1000.0, "2999-01-01");

        List<Integer> atRisk = segmentService.evaluate("deal", def("all", predicate("at_risk")));
        List<Integer> riskHigh = segmentService.evaluate("deal", def("all", predicate("risk_high")));
        List<Integer> closeOverdue = segmentService.evaluate("deal", def("all", predicate("risk_close_overdue")));

        assertTrue(atRisk.contains(overdue.getId()));
        assertTrue(riskHigh.contains(overdue.getId()));
        assertTrue(closeOverdue.contains(overdue.getId()));
        assertFalse(closeOverdue.contains(future.getId()));
    }

    @Test
    void dealRiskPredicate_notApplicableToCompany_throws() {
        assertThrows(BadRequestException.class, () -> evaluate(def("all", predicate("at_risk"))));
    }

    @Test
    void companyField_websiteContains_matches() {
        Company withSite = new Company();
        withSite.setName("Site Co " + unique());
        withSite.setWebsite("https://acme.example.com");
        withSite.setWorkspaceId(workspace.getId());
        companyMapper.insert(withSite);
        Company noSite = companyWithIndustry("No Site " + unique(), "misc");

        List<Integer> ids = segmentService.evaluate("company", def("all", field("website", "contains", "acme")));

        assertTrue(ids.contains(withSite.getId()));
        assertFalse(ids.contains(noSite.getId()));
    }

    @Test
    void companyField_createdBeforeAndAfter_boundsOnCreatedAt() {
        Company company = companyWithIndustry("Dated " + unique(), "misc");

        assertTrue(segmentService.evaluate("company", def("all", field("created", "before", "2999-01-01"))).contains(company.getId()));
        assertFalse(segmentService.evaluate("company", def("all", field("created", "after", "2999-01-01"))).contains(company.getId()));
    }

    @Test
    void dealField_actualValueGte_matches() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal big = new Deal();
        big.setName("Big " + unique());
        big.setWorkspaceId(workspace.getId());
        big.setOwnerId(currentUser.getId());
        big.setValue(0.0);
        big.setActualValue(900.0);
        big.setCurrency("JPY");
        big.setPipelineId(pipeline.getId());
        big.setStageId(stage.getId());
        big.setCompanyId(company.getId());
        dealMapper.insert(big);
        Deal small = dealWith(pipeline, stage, company, 0.0, null);

        List<Integer> ids = segmentService.evaluate("deal", def("all", field("actual_value", "gte", "500")));

        assertTrue(ids.contains(big.getId()));
        assertFalse(ids.contains(small.getId()));
    }
}

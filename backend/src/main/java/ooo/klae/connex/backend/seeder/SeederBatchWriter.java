package ooo.klae.connex.backend.seeder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DealStageHistoryMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.PersonEmploymentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.seeder.SeedDataGenerator.PipelineSeed;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Persists one workspace through application mapper SQL using a MyBatis batch executor.
 *
 * <p>Dependency boundaries and every 500 single-row statements are flushed explicitly.
 * Mapper return values are deliberately ignored because the batch executor returns
 * sentinels until flush; the summary is derived from the deterministic generation plan.
 * Only single-row insert statements are issued: MyBatis cannot assign generated keys for
 * multi-row {@code foreach} inserts under {@code ExecutorType.BATCH}, while single-row
 * statements receive their keys at each flush.
 */
@Lazy
@Component
public class SeederBatchWriter {

    private static final int BATCH_SIZE = 500;
    private static final String[] DEAL_PERSON_ROLES = {
        "Decision Maker",
        "Champion",
        "Stakeholder"
    };

    private final SeedDataGenerator generator;
    private final TenantContext tenantContext;
    private final SqlSessionTemplate batchSession;
    private final OrganizationMapper organizationMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final OrgMemberMapper orgMemberMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final PersonEmploymentMapper personEmploymentMapper;
    private final DealMapper dealMapper;
    private final DealStageHistoryMapper dealStageHistoryMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final AttachmentMapper attachmentMapper;

    public SeederBatchWriter(
            SqlSessionFactory sqlSessionFactory,
            SeedDataGenerator generator,
            TenantContext tenantContext) {
        this.generator = generator;
        this.tenantContext = tenantContext;
        this.batchSession = new SqlSessionTemplate(sqlSessionFactory, ExecutorType.BATCH);
        this.organizationMapper = batchSession.getMapper(OrganizationMapper.class);
        this.workspaceMapper = batchSession.getMapper(WorkspaceMapper.class);
        this.userMapper = batchSession.getMapper(UserMapper.class);
        this.orgMemberMapper = batchSession.getMapper(OrgMemberMapper.class);
        this.pipelineMapper = batchSession.getMapper(PipelineMapper.class);
        this.tagMapper = batchSession.getMapper(TagMapper.class);
        this.companyMapper = batchSession.getMapper(CompanyMapper.class);
        this.personMapper = batchSession.getMapper(PersonMapper.class);
        this.personEmploymentMapper = batchSession.getMapper(PersonEmploymentMapper.class);
        this.dealMapper = batchSession.getMapper(DealMapper.class);
        this.dealStageHistoryMapper = batchSession.getMapper(DealStageHistoryMapper.class);
        this.activityMapper = batchSession.getMapper(ActivityMapper.class);
        this.noteMapper = batchSession.getMapper(NoteMapper.class);
        this.taskMapper = batchSession.getMapper(TaskMapper.class);
        this.attachmentMapper = batchSession.getMapper(AttachmentMapper.class);
    }

    SeedRunSummary.WorkspaceSummary write(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceIndex,
            LocalDate anchorDate) {
        Organization organization = generator.organization(workspaceSeed, workspaceIndex);
        organizationMapper.insert(organization);
        flush();
        requireGeneratedId("organization", organization.getId());

        Workspace workspace = generator.workspace(workspaceSeed, workspaceIndex, organization.getId());
        workspaceMapper.insert(workspace);
        flush();
        requireGeneratedId("workspace", workspace.getId());

        List<User> users = generator.users(workspaceSeed, workspaceIndex);
        for (User user : users) {
            userMapper.insert(user);
        }
        flush();
        users.forEach(user -> requireGeneratedId("app_user", user.getId()));

        for (int index = 0; index < users.size(); index++) {
            User user = users.get(index);
            orgMemberMapper.addMember(
                organization.getId(),
                user.getId(),
                index == 0 ? "owner" : "admin"
            );
            workspaceMapper.addMember(
                workspace.getId(),
                user.getId(),
                generator.userRole(index)
            );
        }
        flush();

        TenantScope previous = captureTenantScope();
        tenantContext.set(
            workspace.getId(),
            organization.getId(),
            users.getFirst().getId(),
            "owner",
            null
        );
        try {
            return writeTenantData(
                profile,
                workspaceSeed,
                workspaceIndex,
                anchorDate,
                organization,
                workspace,
                users
            );
        } finally {
            restoreTenantScope(previous);
        }
    }

    private SeedRunSummary.WorkspaceSummary writeTenantData(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceIndex,
            LocalDate anchorDate,
            Organization organization,
            Workspace workspace,
            List<User> users) {
        List<PipelineSeed> pipelines = writePipelines(workspace.getId());
        List<Tag> tags = writeTags(workspace.getId());
        List<Company> companies = writeCompanies(
            profile, workspaceSeed, workspaceIndex, workspace.getId(), users, tags, anchorDate);
        PersonWriteResult personResult = writePersons(
            profile, workspaceSeed, workspaceIndex, workspace.getId(), users, companies, tags, anchorDate);
        DealWriteResult dealResult = writeDeals(
            profile,
            workspaceSeed,
            workspace.getId(),
            users,
            companies,
            personResult.persons(),
            pipelines,
            tags,
            anchorDate
        );
        writeActivities(
            profile, workspaceSeed, workspace.getId(), users, personResult.persons(), dealResult.deals(), anchorDate);
        writeNotes(
            profile, workspaceSeed, workspace.getId(), users, personResult.persons(), dealResult.deals(), anchorDate);
        writeTasks(
            profile, workspaceSeed, workspace.getId(), users, personResult.persons(), dealResult.deals(), anchorDate);
        writeAttachments(
            profile,
            workspaceSeed,
            workspaceIndex,
            workspace.getId(),
            users,
            companies,
            personResult.persons(),
            dealResult.deals()
        );

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("organization", 1);
        counts.put("workspace", 1);
        counts.put("app_user", users.size());
        counts.put("org_member", users.size());
        counts.put("workspace_member", users.size());
        counts.put("pipeline", pipelines.size());
        counts.put("stage", pipelines.stream().mapToInt(seed -> seed.stages().size()).sum());
        counts.put("tag", tags.size());
        counts.put("company", companies.size());
        counts.put("company_tag", taggedLinkCount(companies.size()));
        counts.put("person", personResult.persons().size());
        counts.put("person_employment", personResult.employmentCount());
        counts.put("person_tag", taggedLinkCount(personResult.persons().size()));
        counts.put("deal", dealResult.deals().size());
        counts.put("deal_person", dealResult.personLinkCount());
        counts.put("deal_stage_history", dealResult.stageHistoryCount());
        counts.put("deal_tag", taggedLinkCount(dealResult.deals().size()));
        counts.put("activity", profile.activities());
        counts.put("note", profile.notes());
        counts.put("task", profile.tasks());
        counts.put("attachment", profile.attachments());
        counts.put("notification", 0);
        return new SeedRunSummary.WorkspaceSummary(
            workspaceIndex + 1,
            workspace.getSlug(),
            counts
        );
    }

    private List<PipelineSeed> writePipelines(int workspaceId) {
        List<Pipeline> pipelines = new ArrayList<>(2);
        for (int index = 0; index < 2; index++) {
            Pipeline pipeline = generator.pipeline(workspaceId, index);
            pipelineMapper.insertPipeline(pipeline);
            pipelines.add(pipeline);
        }
        flush();
        pipelines.forEach(pipeline -> requireGeneratedId("pipeline", pipeline.getId()));

        List<PipelineSeed> result = new ArrayList<>(pipelines.size());
        for (int pipelineIndex = 0; pipelineIndex < pipelines.size(); pipelineIndex++) {
            Pipeline pipeline = pipelines.get(pipelineIndex);
            List<Stage> stages = new ArrayList<>(5);
            for (int position = 0; position < 5; position++) {
                Stage stage = generator.stage(workspaceId, pipeline, pipelineIndex, position);
                pipelineMapper.insertStage(stage);
                stages.add(stage);
            }
            result.add(new PipelineSeed(pipeline, stages));
        }
        flush();
        result.stream()
            .flatMap(seed -> seed.stages().stream())
            .forEach(stage -> requireGeneratedId("stage", stage.getId()));
        return result;
    }

    private List<Tag> writeTags(int workspaceId) {
        List<Tag> tags = new ArrayList<>(12);
        for (int index = 0; index < 12; index++) {
            Tag tag = generator.tag(workspaceId, index);
            tagMapper.insert(tag);
            tags.add(tag);
        }
        flush();
        tags.forEach(tag -> requireGeneratedId("tag", tag.getId()));
        return tags;
    }

    private List<Company> writeCompanies(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceIndex,
            int workspaceId,
            List<User> users,
            List<Tag> tags,
            LocalDate anchorDate) {
        List<Company> companies = new ArrayList<>(profile.companies());
        for (int index = 0; index < profile.companies(); index++) {
            Company company = generator.company(
                workspaceId, workspaceSeed, workspaceIndex, index, users, anchorDate);
            companyMapper.insert(company);
            companies.add(company);
            flushEvery(index);
        }
        flush();
        companies.forEach(company -> requireGeneratedId("company", company.getId()));
        for (int index = 0; index < companies.size(); index++) {
            companyMapper.insertTags(
                workspaceId,
                companies.get(index).getId(),
                tagIds(tags, generator.tagIndexes(workspaceSeed, generator.companySalt(), index))
            );
            flushEvery(index);
        }
        flush();
        return companies;
    }

    private PersonWriteResult writePersons(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceIndex,
            int workspaceId,
            List<User> users,
            List<Company> companies,
            List<Tag> tags,
            LocalDate anchorDate) {
        List<Person> persons = new ArrayList<>(profile.persons());
        for (int index = 0; index < profile.persons(); index++) {
            Person person = generator.person(
                workspaceId, workspaceSeed, workspaceIndex, index, users, companies, anchorDate);
            personMapper.insert(person);
            persons.add(person);
            flushEvery(index);
        }
        flush();
        persons.forEach(person -> requireGeneratedId("person", person.getId()));

        int employmentCount = 0;
        for (int index = 0; index < persons.size(); index++) {
            Person person = persons.get(index);
            List<PersonEmployment> employments = generator.employments(
                workspaceId, workspaceSeed, index, person, companies, anchorDate);
            for (PersonEmployment employment : employments) {
                personEmploymentMapper.insert(employment);
                employmentCount++;
            }
            personMapper.insertTags(
                workspaceId,
                person.getId(),
                tagIds(tags, generator.tagIndexes(workspaceSeed, generator.personSalt(), index))
            );
            flushEvery(index);
        }
        flush();
        return new PersonWriteResult(persons, employmentCount);
    }

    private DealWriteResult writeDeals(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceId,
            List<User> users,
            List<Company> companies,
            List<Person> persons,
            List<PipelineSeed> pipelines,
            List<Tag> tags,
            LocalDate anchorDate) {
        List<Deal> deals = new ArrayList<>(profile.deals());
        Map<Integer, Integer> nextStagePositions = new LinkedHashMap<>();
        for (int index = 0; index < profile.deals(); index++) {
            Deal deal = generator.deal(
                workspaceId, workspaceSeed, index, users, companies, pipelines, anchorDate);
            int position = nextStagePositions.getOrDefault(deal.getStageId(), 0);
            deal.setPosition(position);
            nextStagePositions.put(deal.getStageId(), position + 1);
            dealMapper.insert(deal);
            deals.add(deal);
            flushEvery(index);
        }
        flush();
        deals.forEach(deal -> requireGeneratedId("deal", deal.getId()));

        int personLinkCount = 0;
        int stageHistoryCount = 0;
        int pendingStatements = 0;
        for (int index = 0; index < deals.size(); index++) {
            Deal deal = deals.get(index);
            List<Integer> personIndexes =
                generator.dealPersonIndexes(workspaceSeed, index, persons.size());
            for (int roleIndex = 0; roleIndex < personIndexes.size(); roleIndex++) {
                dealMapper.addPerson(
                    workspaceId,
                    deal.getId(),
                    persons.get(personIndexes.get(roleIndex)).getId(),
                    DEAL_PERSON_ROLES[roleIndex]
                );
                personLinkCount++;
                pendingStatements++;
                flushEveryStatement(pendingStatements);
            }
            PipelineSeed pipeline = pipelineFor(deal, pipelines);
            List<DealStageHistory> histories = generator.dealStageHistory(
                workspaceId, workspaceSeed, index, deal, pipeline, anchorDate);
            for (DealStageHistory history : histories) {
                dealStageHistoryMapper.insert(history);
                stageHistoryCount++;
                pendingStatements++;
                flushEveryStatement(pendingStatements);
            }
            dealMapper.insertTags(
                workspaceId,
                deal.getId(),
                tagIds(tags, generator.tagIndexes(workspaceSeed, generator.dealSalt(), index))
            );
            pendingStatements++;
            flushEveryStatement(pendingStatements);
        }
        flush();
        return new DealWriteResult(deals, personLinkCount, stageHistoryCount);
    }

    private void writeActivities(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceId,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        for (int index = 0; index < profile.activities(); index++) {
            Activity activity = generator.activity(
                workspaceId, workspaceSeed, index, users, persons, deals, anchorDate);
            activityMapper.insert(activity);
            flushEvery(index);
        }
        flush();
    }

    private void writeNotes(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceId,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        for (int index = 0; index < profile.notes(); index++) {
            Note note = generator.note(
                workspaceId, workspaceSeed, index, users, persons, deals, anchorDate);
            noteMapper.insert(note);
            flushEvery(index);
        }
        flush();
    }

    private void writeTasks(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceId,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        Map<String, Integer> nextPositions = new LinkedHashMap<>();
        for (int index = 0; index < profile.tasks(); index++) {
            Task task = generator.task(
                workspaceId, workspaceSeed, index, users, persons, deals, anchorDate);
            int position = nextPositions.getOrDefault(task.getStatus(), 0);
            task.setPosition(position);
            nextPositions.put(task.getStatus(), position + 1);
            taskMapper.insert(task);
            flushEvery(index);
        }
        flush();
    }

    private void writeAttachments(
            SeederProperties.Profile profile,
            long workspaceSeed,
            int workspaceIndex,
            int workspaceId,
            List<User> users,
            List<Company> companies,
            List<Person> persons,
            List<Deal> deals) {
        for (int index = 0; index < profile.attachments(); index++) {
            Attachment attachment = generator.attachment(
                workspaceId,
                workspaceSeed,
                workspaceIndex,
                index,
                users,
                companies,
                persons,
                deals
            );
            attachmentMapper.insert(attachment);
            flushEvery(index);
        }
        flush();
    }

    private static List<Integer> tagIds(List<Tag> tags, List<Integer> indexes) {
        return indexes.stream().map(index -> tags.get(index).getId()).toList();
    }

    private static PipelineSeed pipelineFor(Deal deal, List<PipelineSeed> pipelines) {
        return pipelines.stream()
            .filter(seed -> seed.pipeline().getId() == deal.getPipelineId())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Seeded deal pipeline does not exist in its workspace"));
    }

    private static int taggedLinkCount(int entityCount) {
        return entityCount + entityCount - (entityCount + 3) / 4;
    }

    private static void requireGeneratedId(String table, int id) {
        if (id <= 0) {
            throw new IllegalStateException("Batch insert did not return a generated id for " + table);
        }
    }

    private TenantScope captureTenantScope() {
        if (!tenantContext.isResolved()) {
            return null;
        }
        return new TenantScope(
            tenantContext.getWorkspaceId(),
            tenantContext.getOrgId(),
            tenantContext.getUserId(),
            tenantContext.getRole(),
            tenantContext.getScopeCatalog()
        );
    }

    private void restoreTenantScope(TenantScope previous) {
        tenantContext.clear();
        if (previous != null) {
            tenantContext.set(
                previous.workspaceId(),
                previous.orgId(),
                previous.userId(),
                previous.role(),
                previous.catalog()
            );
        }
    }

    private void flushEvery(int zeroBasedIndex) {
        if ((zeroBasedIndex + 1) % BATCH_SIZE == 0) {
            flush();
        }
    }

    private void flushEveryStatement(int statementCount) {
        if (statementCount % BATCH_SIZE == 0) {
            flush();
        }
    }

    private void flush() {
        batchSession.flushStatements();
    }

    private record PersonWriteResult(List<Person> persons, int employmentCount) {
    }

    private record DealWriteResult(List<Deal> deals, int personLinkCount, int stageHistoryCount) {
    }

    private record TenantScope(
            int workspaceId,
            int orgId,
            int userId,
            String role,
            String catalog) {
    }
}
